package cn.ripplecraft.netherway.core;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端升级流程的状态机：收到凭证 → 打洞 → 成功则切换连接。
 *
 * <p>整个过程对玩家是后台进行的：此刻他已经通过既有的中转隧道正常游戏，
 * 升级失败就安静地留在原连接上，不打断任何事情。
 *
 * <p>这个类不引用任何 Minecraft 类型，因此可以脱离游戏做单元测试。
 */
public final class UpgradeController {

    public enum State {
        /** 未开始，或已回到初始态。 */
        IDLE,
        /** agent 正在打洞。 */
        PUNCHING,
        /** 已切换到 P2P 连接。 */
        UPGRADED,
        /** 本次会话已放弃升级，不再重试。 */
        GAVE_UP
    }

    private final ClientBridge bridge;
    private final Timings timings;
    /** 凭证缓存；null 表示不缓存（每次下发都会刷新，供下次启动预热用）。 */
    private final CredentialCache cache;
    /** 预热控制器；null 表示无预热。就绪的预热隧道会被升级流程直接复用。 */
    private final WarmupController warmup;
    private final AtomicReference<State> state = new AtomicReference<State>(State.IDLE);

    /**
     * 转移锁：所有复合状态转移（CAS + 关联字段）都在它下面做。锁内绝不做
     * IO 或 {@link AgentProcess#close()}（最长阻塞 3 秒），netty 线程会经
     * {@link #onCredentials}/{@link #shutdown} 拿这把锁。
     */
    private final Object transition = new Object();
    /**
     * 代际号，{@link #shutdown()} 每次复位时递增。worker 线程启动时记下
     * 当时的代际，之后每次状态转移都要验代——玩家真退出触发 shutdown 复位
     * 到 IDLE 后，还在跑的旧 worker 若无条件 {@code giveUp}，会把状态覆写成
     * GAVE_UP，本会话的直连从此锁死。guarded by {@link #transition}。
     */
    private long epoch;

    private volatile AgentProcess agent;
    private volatile String activeKey;
    /**
     * 本轮升级等待终态的上限（凭证优先，见 {@link Timings#outcomeWaitMs(long)}），
     * 经 {@link WarmupController.UpgradeGate} 公布给预热的让路等待定界。
     * 让路等的是这轮打洞的实际预算——那可能来自服务端下发的凭证，
     * 预热拿自己的本地配置去猜会猜短，到点自以为对方卡死。
     */
    private volatile long punchWaitBoundMs;
    /** 成功时的 READY 事件，供切换落地后的结果回执取 rtt/耗时。 */
    private volatile AgentEvent lastReady;
    /** 成功回执只发一次（服务端每次重连都会重发凭证）。 */
    private volatile boolean upgradeReported;
    /** 经直连条目进服（采认）为 true：首个回执时顺带提示玩家一句。 */
    private volatile boolean adoptedDirect;

    public UpgradeController(ClientBridge bridge, Timings timings) {
        this(bridge, timings, null, null);
    }

    public UpgradeController(ClientBridge bridge, Timings timings,
                             CredentialCache cache, WarmupController warmup) {
        this.bridge = bridge;
        this.timings = timings == null ? Timings.defaults() : timings.normalized();
        this.cache = cache;
        this.warmup = warmup;
        if (warmup != null) {
            // 反向观察口：预热每轮打洞前让路给正在打洞的升级。两个方向合起来
            // 才堵得住「同 NAT 并发打两个洞互相干扰」——谁后到谁等。
            warmup.setUpgradeGate(new WarmupController.UpgradeGate() {
                @Override
                public boolean punching() {
                    return state.get() == State.PUNCHING;
                }

                @Override
                public long punchWaitBoundMs() {
                    long b = punchWaitBoundMs;
                    return b > 0 ? b : UpgradeController.this.timings.outcomeWaitMs();
                }
            });
        }
    }

    public State state() {
        return state.get();
    }

    /**
     * 收到服务端下发的凭证。
     *
     * <p>切换连接后玩家会重新登录一次，服务端会再下发一次凭证——必须识别出
     * 这是同一个房间的重复下发并忽略，否则会陷入「升级→重连→再升级」的死循环。
     *
     * @return true 表示本次调用启动了升级流程
     */
    public boolean onCredentials(final Credentials raw) {
        if (raw == null) {
            return false;
        }
        // 内嵌会合点模式下服务端不写地址（它未必知道自己的公网入口），
        // 由这里用玩家正连着的地址补齐。必须赶在落盘之前：缓存里那份将来
        // 要供预热直接使用，而预热跑在玩家还没连任何服务器的时候。
        final Credentials cred = withRendezvousAddress(raw);

        // 每次下发（含重复下发）都刷新缓存：参数可能轮换过，文件修改时间
        // 也用作「最近用过的房间」排序。写盘在后台线程做，netty 线程不碰磁盘。
        rememberAsync(cred);

        if (cred.dedupKey().equals(activeKey)) {
            State s = state.get();
            if (s == State.UPGRADED || s == State.PUNCHING) {
                bridge.info("已在处理房间 " + cred.room() + " 的直连，忽略重复凭证");
                if (s == State.UPGRADED) {
                    // 重复凭证经新连接送达，说明切换已真正落地且频道可用，
                    // 这是回传成功回执最可靠的时机。
                    reportUpgradedOnce(cred.room());
                }
                return false;
            }
            if (s == State.GAVE_UP) {
                // 本次会话已判定此房间打洞不通，别反复折腾玩家的网络
                bridge.debug("本次会话已放弃房间 " + cred.room() + " 的直连，忽略重复凭证");
                return false;
            }
        }

        // -1 表示 CAS 失败（epoch 从 0 起只增不减，取不到负值）
        final long gen;
        synchronized (transition) {
            if (state.compareAndSet(State.IDLE, State.PUNCHING)) {
                activeKey = cred.dedupKey();
                punchWaitBoundMs = timings.outcomeWaitMs(cred.punchTimeoutMs());
                gen = epoch;
            } else {
                gen = -1;
            }
        }
        if (gen < 0) {
            bridge.debug("当前状态为 " + state.get() + "，忽略本次凭证");
            return false;
        }

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runUpgrade(cred, gen);
            }
        }, "netherway-upgrade");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private void runUpgrade(Credentials cred, long gen) {
        // 预热隧道已就绪时直接复用：对同一房间再起一个 agent 纯属浪费，
        // 日志里还会出现两套打洞记录。预热还在打洞或已失败则走既有流程
        // （下发的凭证可能比缓存新，比如 secret 轮换过）。
        if (reuseWarmTunnel(cred, gen)) {
            return;
        }
        // 预热正有一轮打洞在飞时先等它出结果：同 NAT 并发打两个洞会互相
        // 干扰（2026-08-09 实测两边都受伤）。等出来若恰好就绪就直接复用，
        // 失败则接着走自己的流程——玩家此刻在中转连接上正常游戏，等得起。
        if (awaitWarmupAttempt() && reuseWarmTunnel(cred, gen)) {
            return;
        }
        AgentProcess proc = null;
        try {
            Platform platform = Platform.detect();
            bridge.info("准备直连：平台 " + platform + "，房间 " + cred.room()
                    + "（" + cred.backendId() + "）");
            // toString 只列参数键名不含值，可以放心进日志
            bridge.debug("收到的凭证: " + cred);

            Path cacheDir = bridge.cacheDirectory();
            Path exe = new BinaryStore(cacheDir, platform).ensureExtracted();
            Path agentLog = cacheDir.resolve("tunnel.log");
            bridge.debug("agent 二进制: " + exe);
            bridge.debug("启动 agent: " + AgentProcess.describeCommand(
                    AgentProcess.buildCommand(exe, cred, timings, agentLog)));

            proc = AgentProcess.start(exe, cred, timings, cacheDir, agentLog,
                    new AgentProcess.Listener() {
                        @Override
                        public void onEvent(AgentEvent event) {
                            bridge.debug("agent 事件: " + event);
                        }

                        @Override
                        public void onStderrLine(String line) {
                            bridge.debug("agent: " + line);
                        }
                    });
            if (!attach(proc, gen)) {
                // 启动进程期间 shutdown 已复位：这个 agent 没登记进任何人的
                // 视野，不就地收掉就只剩 JVM 退出时的 shutdown hook 兜底
                proc.close();
                bridge.debug("忽略过期升级：复位后不再接管刚启动的 agent");
                return;
            }

            // 与 agent 的 -timeout 同源（凭证优先），再留 startupGrace 的余量：
            // agent 正常应先于这个窗口自行报 failed，窗口只兜进程卡死的底
            long waitMs = timings.outcomeWaitMs(cred.punchTimeoutMs());
            bridge.debug("等待打洞结果，最多 " + waitMs
                    + "ms（agent 详细日志: " + agentLog + "）");
            AgentEvent outcome = proc.awaitOutcome(waitMs);

            if (outcome == null) {
                giveUp(proc, cred, "等待直连结果超时", gen);
                return;
            }
            if (outcome.type() != AgentEvent.Type.READY) {
                String why = outcome.reason() == null ? "打洞未成功" : outcome.reason();
                giveUp(proc, cred, why, gen);
                return;
            }

            // 到这里隧道已经通过 Minecraft 握手验证过，切换是安全的
            final int port = outcome.port();
            long rtt = outcome.rttMs();
            if (!markUpgraded(outcome, gen)) {
                proc.close();
                bridge.debug("忽略过期升级：复位后不再切换（房间 " + cred.room() + "）");
                return;
            }
            bridge.info("直连就绪，端口 " + port + "，延迟 " + rtt + "ms，用时 "
                    + outcome.elapsedMs() + "ms");

            final String msg = rtt > 0
                    ? "已建立直连（延迟 " + rtt + "ms），正在切换…"
                    : "已建立直连，正在切换…";
            bridge.runOnGameThread(new Runnable() {
                @Override
                public void run() {
                    bridge.notifyPlayer(msg);
                    bridge.connectTo("127.0.0.1", port);
                }
            });

        } catch (Platform.UnsupportedPlatformException e) {
            // 没有对应平台的二进制，重试也没意义
            giveUp(proc, cred, "当前系统不支持直连: " + e.getMessage(), gen);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            giveUp(proc, cred, "升级过程被中断", gen);
        } catch (Throwable t) {
            // Exception 之外还必须接住 Error：1.7.10 挤着几百个 mod 的类路径上
            // NoClassDefFoundError/LinkageError 并不罕见，逃逸出去状态就永远
            // 停在 PUNCHING，本会话再也无法升级
            bridge.warn("建立直连失败", t);
            giveUp(proc, cred, t.getMessage(), gen);
        }
    }

    /** worker 把刚启动的 agent 登记进控制器；复位后登记被拒，进程由 worker 自行收掉。 */
    private boolean attach(AgentProcess proc, long gen) {
        synchronized (transition) {
            if (gen != epoch) {
                return false;
            }
            agent = proc;
            return true;
        }
    }

    /** worker 申请把状态置为 UPGRADED；shutdown 复位过（换代）则拒绝。 */
    private boolean markUpgraded(AgentEvent ready, long gen) {
        synchronized (transition) {
            if (gen != epoch) {
                return false;
            }
            lastReady = ready;
            state.set(State.UPGRADED);
            return true;
        }
    }

    /**
     * 预热正在打洞时等它出结果（成败皆可），上限取预热公布的这轮预算
     * （{@link WarmupController#punchWaitBoundMs}）——预热的 agent 超时可能
     * 来自服务端下发的凭证，远长于本地配置；用本地配置定界会提前到点、
     * 起自己的 agent 与预热并发打洞，正是让路要避免的事。轮询在预热出
     * 结果时立即退出，界只兜预热卡死的底。返回 true 表示确实等过——
     * 调用方应再试一次复用（预热可能恰好就绪了）。
     */
    private boolean awaitWarmupAttempt() {
        if (warmup == null || !warmup.punching()) {
            return false;
        }
        bridge.info("预热正在打洞，等它出结果再决定（避免并发打洞互相干扰）");
        long deadline = System.currentTimeMillis() + warmup.punchWaitBoundMs();
        while (warmup.punching() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(timings.probeIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return true;
    }

    /**
     * 复用已就绪的预热隧道，成功返回 true。
     *
     * <p>隧道健康只由「进程还活着」担保（frp 的 keepTunnelOpen 会自行维护
     * 会话）；极端情况下切换会失败，玩家重连一次即可回到既有流程——
     * 这与点击直连条目失败的体验一致，不为它增加一套探测。
     */
    private boolean reuseWarmTunnel(Credentials cred, long gen) {
        if (warmup == null) {
            return false;
        }
        AgentEvent readyEv = warmup.readyEvent(cred.dedupKey());
        if (readyEv == null) {
            return false;
        }
        final int port = readyEv.port();
        long rtt = readyEv.rttMs();
        // agent 字段保持 null：隧道归 WarmupController 管，shutdown() 不会误杀
        if (!markUpgraded(readyEv, gen)) {
            // 本轮升级已被 shutdown 作废，也别落回冷启动流程
            bridge.debug("忽略过期升级：复位后不再复用预热隧道");
            return true;
        }
        bridge.info("复用预热隧道，端口 " + port + "，延迟 " + rtt + "ms，正在切换");
        final String msg = rtt > 0
                ? "已建立直连（延迟 " + rtt + "ms，预热），正在切换…"
                : "已建立直连（预热），正在切换…";
        bridge.runOnGameThread(new Runnable() {
            @Override
            public void run() {
                bridge.notifyPlayer(msg);
                bridge.connectTo("127.0.0.1", port);
            }
        });
        return true;
    }

    /**
     * 玩家经服务器列表的直连条目进服时由平台层调用：当前连接本来就走在
     * 预热隧道上，不需要任何升级动作，只需把状态机置为 UPGRADED——随后
     * 服务端照常下发的凭证会命中 {@link #onCredentials} 的重复凭证分支，
     * 回执成功并提示玩家。
     *
     * @param cred  预热隧道对应的凭证（{@link WarmupController#credentialsForPort}）
     * @param ready 预热时的 READY 事件，回执从中取 rtt/耗时
     * @return true 表示采认成功
     */
    public boolean adoptDirectConnection(Credentials cred, AgentEvent ready) {
        if (cred == null || ready == null) {
            return false;
        }
        boolean adopted;
        synchronized (transition) {
            adopted = state.compareAndSet(State.IDLE, State.UPGRADED);
            if (adopted) {
                activeKey = cred.dedupKey();
                lastReady = ready;
                adoptedDirect = true;
            }
        }
        if (!adopted) {
            bridge.debug("当前状态为 " + state.get() + "，不采认直连条目的连接");
            return false;
        }
        bridge.info("本次连接经直连条目建立（房间 " + cred.room() + "，端口 "
                + ready.port() + "），视作已升级");
        return true;
    }

    /** 后台把凭证写进缓存；缓存是尽力而为的优化，失败绝不影响升级流程。 */
    /**
     * 凭证缺会合点地址时，用玩家正连着的服务器地址补齐。
     *
     * <p>地址取自平台层的「玩家选中的那台服务器」而不是当前 socket 的对端：
     * 升级成功后玩家会重连到本机直连条目，那时对端是回环地址，服务端此刻
     * 还会再下发一次凭证（重复分支），用对端地址补就会把回环写进缓存，
     * 下一轮预热便会让 agent 去连自己的回环。{@link ClientBridge#currentServerAddress}
     * 的契约要求实现返回原始服务器，拿不准时返回 null。
     *
     * <p>补不上也照常往下走：agent 那边缺 {@code server} 会响亮报错，
     * 比在这里悄悄拦下更好排查——而缺 {@code serverPort} 反而会静默落到
     * frp 的默认 7000，所以两个键必须一起补，绝不能只补一个。
     */
    private Credentials withRendezvousAddress(Credentials cred) {
        if (!cred.needsRendezvousAddress()) {
            return cred;
        }
        ServerCandidates.Address at = bridge.currentServerAddress();
        if (at == null) {
            bridge.warn("凭证未带会合点地址，而当前连接的服务器地址也推导不出来，"
                    + "直连多半会失败（服务端若开着 server.rendezvous，"
                    + "请确认玩家是从服务器列表进服的）", null);
            return cred;
        }
        bridge.debug("凭证未带会合点地址，按当前连接补为 " + at);
        return cred.rendezvousAt(at.host, at.port);
    }

    private void rememberAsync(final Credentials cred) {
        if (cache == null) {
            return;
        }
        // 没补上会合点地址的凭证绝不落盘：缓存按房间同文件覆盖，这份残废版
        // 会把之前带地址的好凭证盖掉，下次启动预热直接瘫痪（2026-08-09 实测：
        // 切换后地址推导不出，重复下发的凭证把缓存污染，预热从此起不来）。
        // 跳过的代价可忽略——补不上地址意味着推导失败，参数真轮换过的新凭证
        // 一定是经真实服务器地址的连接送达的，那条路补得上。
        if (cred.needsRendezvousAddress()) {
            bridge.debug("凭证缺会合点地址，不写入缓存（保留缓存中已有的版本）");
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    cache.store(cred);
                    bridge.debug("凭证已缓存，下次启动将预热房间 " + cred.room() + " 的直连");
                } catch (Exception e) {
                    bridge.debug("缓存凭证失败（只影响下次启动的预热）: " + e);
                }
            }
        }, "netherway-credcache");
        worker.setDaemon(true);
        worker.start();
    }

    private void giveUp(AgentProcess proc, Credentials cred, String reason, long gen) {
        boolean stale;
        synchronized (transition) {
            stale = gen != epoch;
            if (!stale) {
                state.set(State.GAVE_UP);
                agent = null;
            }
        }
        // close 最长阻塞 3 秒，放在转移锁外；重复 close 是幂等的
        if (proc != null) {
            proc.close();
        }
        if (stale) {
            // shutdown 已复位（或已进入新一轮升级）：状态不归这个 worker 管。
            // 覆写成 GAVE_UP 会把玩家重进后的正常升级一并锁死，
            // 还会往服务端发一条假的失败回执。
            bridge.debug("忽略过期升级的放弃（" + reason + "）");
            return;
        }
        // 升级失败对玩家无感：他仍在原有的中转连接上正常游戏，
        // 所以只记日志，不去打扰他。
        bridge.info("放弃直连，继续使用当前线路（" + reason + "）");
        // 此刻中转连接还活着，回执能稳稳送出去；服主在服务端日志里
        // 就能看到失败原因，不用挨个找玩家要客户端日志。
        sendReport(UpgradeReport.gaveUp(cred.room(), reason));
    }

    /** 成功回执。切换会断开旧连接，发早了可能没送出去就断了，所以放到落地之后。 */
    private void reportUpgradedOnce(String room) {
        if (upgradeReported) {
            return;
        }
        upgradeReported = true;
        AgentEvent ready = lastReady;
        sendReport(UpgradeReport.upgraded(room,
                ready == null ? 0 : ready.rttMs(),
                ready == null ? 0 : ready.elapsedMs()));
        if (adoptedDirect) {
            // 采认路径没有「正在切换」的过程提示；凭证经新连接送达说明玩家
            // 已进世界，此刻补一句确认不会落空。
            final String msg = ready != null && ready.rttMs() > 0
                    ? "已通过预热直连进入服务器（延迟 " + ready.rttMs() + "ms）"
                    : "已通过预热直连进入服务器";
            bridge.runOnGameThread(new Runnable() {
                @Override
                public void run() {
                    bridge.notifyPlayer(msg);
                }
            });
        }
    }

    private void sendReport(UpgradeReport report) {
        try {
            bridge.sendToServer(report.encode());
            bridge.debug("已回传直连结果: " + report);
        } catch (RuntimeException e) {
            // 回执是尽力而为的诊断信息，失败绝不能影响升级流程
            bridge.debug("回传直连结果失败: " + e);
        }
    }

    /**
     * 玩家离开服务器时调用：停掉 agent 并复位。
     *
     * <p>注意升级导致的重连也会触发断开，此时不能停掉隧道——正是它在承载
     * 即将建立的新连接。靠状态区分：UPGRADED 表示这次断开是我们自己造成的。
     */
    public void onDisconnected() {
        if (state.get() == State.UPGRADED) {
            return;
        }
        shutdown();
    }

    /**
     * 彻底停止并复位，游戏退出或玩家切换服务器时调用。
     *
     * <p>只管自己起的 agent——预热隧道归 {@link WarmupController}，
     * 它要活到游戏进程结束，承载服务器列表里的直连条目。
     */
    public void shutdown() {
        AgentProcess proc;
        synchronized (transition) {
            // 换代：还在跑的旧 worker 之后的任何状态转移都会被拒
            epoch++;
            proc = agent;
            agent = null;
            activeKey = null;
            punchWaitBoundMs = 0;
            lastReady = null;
            upgradeReported = false;
            adoptedDirect = false;
            state.set(State.IDLE);
        }
        // close 最长阻塞 3 秒，不能占着转移锁
        if (proc != null) {
            proc.close();
        }
    }
}
