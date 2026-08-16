package cn.ripplecraft.netherway.core;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import cn.ripplecraft.netherway.core.telemetry.QualityObserver;
import cn.ripplecraft.netherway.core.telemetry.QualitySummary;

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
        /**
         * 本次会话已放弃主动打洞，不再重试。预热隧道后续就绪时仍可
         * 就地切换救活（{@link #rescueFromWarmTunnel}，有次数上限）。
         */
        GAVE_UP
    }

    private final ClientBridge bridge;
    private final Timings timings;
    /** 凭证缓存；null 表示不缓存（每次下发都会刷新，供下次启动预热用）。 */
    private final CredentialCache cache;
    /** 预热控制器；null 表示无预热。就绪的预热隧道会被升级流程直接复用。 */
    private final WarmupController warmup;
    private final QualityObserver quality;
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
    /** 当前目标的完整客户端凭证，含 MC 入口；直连条目进服后用它补回来源。 */
    private volatile Credentials activeCredentials;
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
    /** READY 后已经发起重连、但平台层尚未确认落地。 */
    private volatile boolean redirectPending;
    /** READY 后已排队到游戏线程、但重定向任务尚未执行。guarded by transition。 */
    private boolean redirectScheduled;
    private volatile QualitySummary.Source telemetrySource = QualitySummary.Source.UNKNOWN;
    /** 本轮升级的隧道方案，随凭证确定；observe 统一盖章。 */
    private volatile QualitySummary.Backend telemetryBackend = QualitySummary.Backend.UNKNOWN;
    /**
     * agent 最近一次探测出的 NAT 形态。NAT 是宿主网络属性而非单轮属性，
     * 因此跨轮保留、shutdown 不清零；网络切换后由下一个 agent 事件刷新。
     */
    private volatile QualitySummary.Nat telemetryNat = QualitySummary.Nat.UNKNOWN;

    /** 游玩中预热就绪后是否立即切换（client.redirectOnWarmReady），默认开。 */
    private volatile boolean redirectOnWarmReady = true;
    /** 每个房间本会话最多自动救援次数；用完后只留路由表，等玩家自行重连。 */
    private static final int MAX_WARM_RESCUES = 2;
    /**
     * 各房间已用的自动救援次数。与 telemetryNat 一样跨 {@link #shutdown()}
     * 存活：「救援→隧道死→重进→升级又败→再救援」的循环每圈都经过一次
     * shutdown，随会话清零的话上限就形同虚设。guarded by {@link #transition}。
     */
    private final Map<String, Integer> warmRescueCounts = new HashMap<String, Integer>();

    public UpgradeController(ClientBridge bridge, Timings timings) {
        this(bridge, timings, null, null, QualityObserver.NOOP);
    }

    public UpgradeController(ClientBridge bridge, Timings timings,
                             CredentialCache cache, WarmupController warmup) {
        this(bridge, timings, cache, warmup, QualityObserver.NOOP);
    }

    public UpgradeController(ClientBridge bridge, Timings timings,
                             CredentialCache cache, WarmupController warmup,
                             QualityObserver quality) {
        this.bridge = bridge;
        this.timings = timings == null ? Timings.defaults() : timings.normalized();
        this.cache = cache;
        this.warmup = warmup;
        this.quality = quality == null ? QualityObserver.NOOP : quality;
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
            // 正向救援口：升级已放弃、玩家还经中转挂在服务器上时，预热隧道
            // 一旦就绪就把连接就地切换过去。打洞互斥保证此回调必然晚于同轮
            // giveUp 的 GAVE_UP 提交（门闩在 runUpgrade 的 finally 里才释放），
            // 单触发点即可，不存在「回调看到 PUNCHING 而错过」的竞态。
            warmup.setReadyObserver(new WarmupController.ReadyObserver() {
                @Override
                public void onWarmTunnelReady(Credentials cred, AgentEvent event) {
                    rescueFromWarmTunnel(cred, event);
                }
            });
        }
    }

    /** 游玩中预热就绪后是否立即切换；平台层接线时按客户端配置设置。 */
    public void setRedirectOnWarmReady(boolean enabled) {
        this.redirectOnWarmReady = enabled;
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
        final Credentials cred = withServerOrigin(raw);

        // 每次下发（含重复下发）都刷新缓存：参数可能轮换过，文件修改时间
        // 也用作「最近用过的房间」排序。写盘在后台线程做，netty 线程不碰磁盘。
        rememberAsync(cred);

        if (cred.dedupKey().equals(activeKey)) {
            State s = state.get();
            if (s == State.UPGRADED || s == State.PUNCHING) {
                bridge.info(L10n.tr("upgrade.dupActive", cred.room()));
                if (s == State.UPGRADED) {
                    // 重复凭证经新连接送达，说明切换已真正落地且频道可用，
                    // 这是回传成功回执最可靠的时机。
                    reportUpgradedOnce(cred.room());
                }
                return false;
            }
            if (s == State.GAVE_UP) {
                // 本次会话已判定此房间打洞不通，别反复折腾玩家的网络
                bridge.debug(L10n.tr("upgrade.dupGaveUp", cred.room()));
                return false;
            }
        }

        // -1 表示 CAS 失败（epoch 从 0 起只增不减，取不到负值）
        final long gen;
        synchronized (transition) {
            if (state.compareAndSet(State.IDLE, State.PUNCHING)) {
                activeKey = cred.dedupKey();
                activeCredentials = cred;
                punchWaitBoundMs = timings.outcomeWaitMs(cred.punchTimeoutMs());
                telemetryBackend = QualitySummary.Backend.fromBackendId(cred.backendId());
                gen = epoch;
            } else {
                gen = -1;
            }
        }
        if (gen < 0) {
            bridge.debug(L10n.tr("upgrade.ignoreState", state.get()));
            return false;
        }

        observe(QualitySummary.of(QualitySummary.Path.UPGRADE,
                QualitySummary.Stage.STARTED, QualitySummary.Outcome.STARTED));

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
        // 多服务预热会依次打洞。升级侧在起自己的 agent 前先预留门闩：
        // 等当前预热收尾，同时阻止它抢先开始下一个服务。
        final boolean reservedWarmup = warmup != null && warmup.reserveUpgradePunch();
        AgentProcess proc = null;
        try {
            // 等待期间当前房间可能恰好 READY，再试一次复用。
            if (reuseWarmTunnel(cred, gen)) {
                return;
            }
            // 从这里开始已确定不走预热复用；平台检测、解压和进程
            // 启动都可能在 STARTING 事件前失败，所以要先固定失败来源。
            if (!selectColdAgent(gen)) {
                bridge.debug(L10n.tr("upgrade.staleCold"));
                return;
            }
            Platform platform = Platform.detect();
            bridge.info(L10n.tr("upgrade.preparing", platform, cred.room(), cred.backendId()));
            // toString 只列参数键名不含值，可以放心进日志
            bridge.debug(L10n.tr("upgrade.credReceived", cred));

            Path cacheDir = bridge.cacheDirectory();
            Path exe;
            try {
                exe = new BinaryStore(cacheDir, platform).ensureExtracted();
            } catch (Throwable t) {
                bridge.warn(L10n.tr("upgrade.extractFailed"), t);
                giveUp(proc, cred, t.getMessage(), gen,
                        QualitySummary.FailureStage.EXTRACT,
                        QualitySummary.FailureCode.BINARY_EXTRACT_FAILED);
                return;
            }
            Path agentLog = cacheDir.resolve("tunnel.log");
            bridge.debug(L10n.tr("upgrade.agentBinary", exe));
            bridge.debug(L10n.tr("upgrade.agentCommand", AgentProcess.describeCommand(
                    AgentProcess.buildCommand(exe, cred, timings, agentLog))));

            try {
                proc = AgentProcess.start(exe, cred, timings, cacheDir, agentLog,
                        new AgentProcess.Listener() {
                        @Override
                        public void onEvent(AgentEvent event) {
                            bridge.debug(L10n.tr("upgrade.agentEvent", event));
                            if (event.type() == AgentEvent.Type.STARTING && isCurrent(gen)) {
                                observe(QualitySummary.of(QualitySummary.Path.UPGRADE,
                                        QualitySummary.Stage.PUNCH_STARTED,
                                        QualitySummary.Outcome.STARTED)
                                        .withSource(QualitySummary.Source.COLD_AGENT));
                            }
                            // 承载中的隧道死了（backend 报错退出）。我们自己
                            // 停 agent 前必然先换代（shutdown）或已离开
                            // UPGRADED（giveUp），能走到这里的 stopped 只剩
                            // 真的隧道丢失。进程被外力硬杀不产生事件，不在
                            // 此覆盖范围内。
                            if (event.type() == AgentEvent.Type.STOPPED && isCurrent(gen)
                                    && state.get() == State.UPGRADED) {
                                observe(QualitySummary.of(QualitySummary.Path.UPGRADE,
                                        QualitySummary.Stage.TUNNEL_LOST,
                                        QualitySummary.Outcome.FAILED)
                                        .withSource(QualitySummary.Source.COLD_AGENT)
                                        .withFailure(QualitySummary.FailureStage.BACKEND,
                                                QualitySummary.FailureCode.BACKEND_EXITED));
                            }
                        }

                        @Override
                        public void onStderrLine(String line) {
                            bridge.debug("agent: " + line);
                        }
                    });
            } catch (Throwable t) {
                bridge.warn(L10n.tr("upgrade.startFailed"), t);
                giveUp(proc, cred, t.getMessage(), gen,
                        QualitySummary.FailureStage.START,
                        QualitySummary.FailureCode.AGENT_START_FAILED);
                return;
            }
            if (!attach(proc, gen)) {
                // 启动进程期间 shutdown 已复位：这个 agent 没登记进任何人的
                // 视野，不就地收掉就只剩 JVM 退出时的 shutdown hook 兜底
                proc.close();
                bridge.debug(L10n.tr("upgrade.staleAttach"));
                return;
            }

            // 与 agent 的 -timeout 同源（凭证优先），再留 startupGrace 的余量：
            // agent 正常应先于这个窗口自行报 failed，窗口只兜进程卡死的底
            long waitMs = timings.outcomeWaitMs(cred.punchTimeoutMs());
            bridge.debug(L10n.tr("upgrade.waitingOutcome", waitMs, agentLog));
            AgentEvent outcome = proc.awaitOutcome(waitMs);

            if (outcome != null) {
                rememberNat(outcome);
            }
            if (outcome == null) {
                giveUp(proc, cred, L10n.tr("reason.outcomeTimeout"), gen,
                        QualitySummary.FailureStage.PROBE,
                        QualitySummary.FailureCode.READY_PROBE_TIMEOUT);
                return;
            }
            if (outcome.type() != AgentEvent.Type.READY) {
                String why = outcome.reason() == null
                        ? L10n.tr("reason.punchFailed") : outcome.reason();
                giveUp(proc, cred, why, gen,
                        QualitySummary.FailureStage.fromWire(outcome.failureStage()),
                        QualitySummary.FailureCode.fromWire(outcome.failureCode()));
                return;
            }

            // 到这里隧道已经通过 Minecraft 握手验证过，切换是安全的
            final int port = outcome.port();
            long rtt = outcome.rttMs();
            if (!markUpgraded(outcome, gen, QualitySummary.Source.COLD_AGENT)) {
                proc.close();
                bridge.debug(L10n.tr("upgrade.staleSwitch", cred.room()));
                return;
            }
            bridge.info(L10n.tr("upgrade.ready", port, rtt, outcome.elapsedMs()));
            observe(QualitySummary.of(QualitySummary.Path.UPGRADE,
                    QualitySummary.Stage.TUNNEL_READY, QualitySummary.Outcome.SUCCESS)
                    .withSource(QualitySummary.Source.COLD_AGENT)
                    .withTimings(outcome.elapsedMs(), outcome.rttMs()));

            final String msg = rtt > 0
                    ? L10n.tr("chat.switching.rtt", rtt)
                    : L10n.tr("chat.switching");
            bridge.runOnGameThread(new Runnable() {
                @Override
                public void run() {
                    runRedirect(gen, QualitySummary.Source.COLD_AGENT, msg, port);
                }
            });

        } catch (Platform.UnsupportedPlatformException e) {
            // 没有对应平台的二进制，重试也没意义
            giveUp(proc, cred, L10n.tr("reason.platformUnsupported", e.getMessage()), gen,
                    QualitySummary.FailureStage.PLATFORM,
                    QualitySummary.FailureCode.PLATFORM_UNSUPPORTED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            giveUp(proc, cred, L10n.tr("reason.interrupted"), gen,
                    QualitySummary.FailureStage.INTERNAL,
                    QualitySummary.FailureCode.INTERRUPTED);
        } catch (Throwable t) {
            // Exception 之外还必须接住 Error：1.7.10 挤着几百个 mod 的类路径上
            // NoClassDefFoundError/LinkageError 并不罕见，逃逸出去状态就永远
            // 停在 PUNCHING，本会话再也无法升级
            bridge.warn(L10n.tr("upgrade.failed"), t);
            giveUp(proc, cred, t.getMessage(), gen,
                    QualitySummary.FailureStage.INTERNAL,
                    QualitySummary.FailureCode.INTERNAL_ERROR);
        } finally {
            if (reservedWarmup) {
                warmup.releaseUpgradePunch();
            }
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
    private boolean markUpgraded(AgentEvent ready, long gen, QualitySummary.Source source) {
        synchronized (transition) {
            if (gen != epoch) {
                return false;
            }
            lastReady = ready;
            telemetrySource = source;
            // READY 与“将发起重定向”必须是同一次状态提交；否则 shutdown
            // 落在两者之间会看见 UPGRADED 却误以为没有未完成的尝试。
            redirectScheduled = true;
            state.set(State.UPGRADED);
            return true;
        }
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
        telemetryBackend = QualitySummary.Backend.fromBackendId(cred.backendId());
        rememberNat(readyEv);
        final int port = readyEv.port();
        long rtt = readyEv.rttMs();
        // agent 字段保持 null：隧道归 WarmupController 管，shutdown() 不会误杀
        if (!markUpgraded(readyEv, gen, QualitySummary.Source.WARMUP_REUSE)) {
            // 本轮升级已被 shutdown 作废，也别落回冷启动流程
            bridge.debug(L10n.tr("upgrade.staleReuse"));
            return true;
        }
        bridge.info(L10n.tr("upgrade.reuseWarm", port, rtt));
        observe(QualitySummary.of(QualitySummary.Path.UPGRADE,
                QualitySummary.Stage.TUNNEL_READY, QualitySummary.Outcome.SUCCESS)
                .withSource(QualitySummary.Source.WARMUP_REUSE)
                .withTimings(readyEv.elapsedMs(), readyEv.rttMs()));
        final String msg = rtt > 0
                ? L10n.tr("chat.switching.warm.rtt", rtt)
                : L10n.tr("chat.switching.warm");
        bridge.runOnGameThread(new Runnable() {
            @Override
            public void run() {
                runRedirect(gen, QualitySummary.Source.WARMUP_REUSE, msg, port);
            }
        });
        return true;
    }

    /**
     * 预热隧道就绪时的救援：玩家经中转在服务器里游玩、本会话升级已放弃
     * （GAVE_UP）的话，就地切换到刚就绪的隧道，不必等玩家断开重连。
     * 典型时序（2026-08-16 CI 实测）：升级冷启动 15 秒超时先败，让路的
     * 预热随后 5 秒打通——没有这条路径玩家只能手动重连才走上直连。
     *
     * <p>只救 GAVE_UP：{@link #activeKey} 在 giveUp 后保留、只被
     * {@link #shutdown()} 清空，恰好同时证明「玩家还连着」且「连的正是
     * 这个房间的服务器」。IDLE（没收到过凭证）无从校验目标，不救；
     * 采用已就绪隧道零打洞成本，与 GAVE_UP「别反复折腾玩家网络」的
     * 本意不冲突。排队到切换执行之间隧道死掉的话，后果与玩家点直连
     * 条目失败一致（回菜单重连一次），不为它加一套探测。
     */
    void rescueFromWarmTunnel(Credentials cred, AgentEvent ready) {
        if (!redirectOnWarmReady || cred == null || ready == null
                || ready.type() != AgentEvent.Type.READY) {
            return;
        }
        final long gen;
        synchronized (transition) {
            if (state.get() != State.GAVE_UP || !cred.dedupKey().equals(activeKey)) {
                return;
            }
            Integer used = warmRescueCounts.get(cred.dedupKey());
            if (used != null && used >= MAX_WARM_RESCUES) {
                bridge.debug(L10n.tr("upgrade.rescueExhausted", cred.room()));
                return;
            }
            warmRescueCounts.put(cred.dedupKey(),
                    Integer.valueOf(used == null ? 1 : used.intValue() + 1));
            // 与 markUpgraded 相同的提交纪律：READY 与「将发起重定向」
            // 必须是同一次状态提交，gen 取自同一临界区。
            // 预热侧凭证必带 origin（缓存拦截无地址凭证），换上它让切换
            // 落地后重复下发凭证的 origin 回补有最可靠的兜底。
            activeCredentials = cred;
            lastReady = ready;
            telemetrySource = QualitySummary.Source.WARMUP_REUSE;
            telemetryBackend = QualitySummary.Backend.fromBackendId(cred.backendId());
            redirectScheduled = true;
            state.set(State.UPGRADED);
            gen = epoch;
        }
        rememberNat(ready);
        bridge.info(L10n.tr("upgrade.rescueSwitch", cred.room(), ready.port()));
        observe(QualitySummary.of(QualitySummary.Path.UPGRADE,
                QualitySummary.Stage.TUNNEL_READY, QualitySummary.Outcome.SUCCESS)
                .withSource(QualitySummary.Source.WARMUP_REUSE)
                .withTimings(ready.elapsedMs(), ready.rttMs()));
        final int port = ready.port();
        long rtt = ready.rttMs();
        final String msg = rtt > 0
                ? L10n.tr("chat.rescue.rtt", rtt)
                : L10n.tr("chat.rescue");
        bridge.runOnGameThread(new Runnable() {
            @Override
            public void run() {
                runRedirect(gen, QualitySummary.Source.WARMUP_REUSE, msg, port);
            }
        });
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
                activeCredentials = cred;
                lastReady = ready;
                adoptedDirect = true;
                telemetrySource = QualitySummary.Source.DIRECT_ENTRY;
                telemetryBackend = QualitySummary.Backend.fromBackendId(cred.backendId());
            }
        }
        rememberNat(ready);
        if (!adopted) {
            bridge.debug(L10n.tr("upgrade.adoptRejected", state.get()));
            return false;
        }
        bridge.info(L10n.tr("upgrade.adopted", cred.room(), ready.port()));
        observe(QualitySummary.of(QualitySummary.Path.UPGRADE,
                QualitySummary.Stage.REDIRECT_LANDED, QualitySummary.Outcome.SUCCESS)
                .withSource(QualitySummary.Source.DIRECT_ENTRY)
                .withTimings(ready.elapsedMs(), ready.rttMs()));
        return true;
    }

    /**
     * 把玩家当前选中的 Minecraft 入口附到凭证，并顺便补齐内嵌会合点地址。
     *
     * <p>入口不是 backend 配置，只是多服务器客户端的本地命名空间。
     * 它取自平台层的「玩家选中的那台服务器」而不是当前 socket 的对端：
     * 升级成功后玩家会重连到本机直连条目，那时对端是回环地址，服务端此刻
     * 还会再下发一次凭证（重复分支），用对端地址补就会把回环写进缓存，
     * 下一轮预热便会让 agent 去连自己的回环。{@link ClientBridge#currentServerAddress}
     * 的契约要求实现返回原始服务器，拿不准时返回 null。
     *
     * <p>经预热条目进服时平台层只能看到回环。此时用采认时保存的
     * {@link #activeCredentials} 回补；这份状态只属于当前连接，换服时会被清空。
     */
    private Credentials withServerOrigin(Credentials cred) {
        ServerCandidates.Address at = bridge.currentServerAddress();
        Credentials active = activeCredentials;
        if (at == null && active != null && active.hasOrigin()
                && active.backendId().equals(cred.backendId())
                && active.room().equals(cred.room())) {
            at = ServerCandidates.Address.of(active.originHost(), active.originPort());
        }
        if (at == null) {
            if (cred.needsRendezvousAddress()) {
                bridge.warn(L10n.tr("upgrade.noRendezvousAddr"), null);
            } else {
                bridge.debug(L10n.tr("upgrade.noOrigin"));
            }
            return cred;
        }
        Credentials withOrigin = cred.withOrigin(at.host, at.port);
        if (withOrigin.needsRendezvousAddress()) {
            bridge.debug(L10n.tr("upgrade.fillRendezvous", at));
            withOrigin = withOrigin.rendezvousAt(at.host, at.port);
        }
        return withOrigin;
    }

    /** 后台把凭证写进缓存；缓存是尽力而为的优化，失败绝不影响升级流程。 */
    private void rememberAsync(final Credentials cred) {
        if (cache == null) {
            return;
        }
        // 没补上会合点地址或入口的凭证绝不落盘：这种不完整版本既无法参与
        // 多服务命名，也可能盖掉之前的可用凭证（2026-08-09 实测：
        // 切换后地址推导不出，重复下发的凭证把缓存污染，预热从此起不来）。
        // 跳过的代价可忽略——补不上地址意味着推导失败，参数真轮换过的新凭证
        // 一定是经真实服务器地址的连接送达的，那条路补得上。
        if (cred.needsRendezvousAddress() || !cred.hasOrigin()) {
            bridge.debug(L10n.tr("upgrade.skipCache"));
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    cache.store(cred);
                    bridge.debug(L10n.tr("upgrade.cached", cred.room()));
                } catch (Exception e) {
                    bridge.debug(L10n.tr("upgrade.cacheFailed", e));
                }
            }
        }, "netherway-credcache");
        worker.setDaemon(true);
        worker.start();
    }

    private void giveUp(AgentProcess proc, Credentials cred, String reason, long gen,
                        QualitySummary.FailureStage failureStage,
                        QualitySummary.FailureCode failureCode) {
        boolean stale;
        QualitySummary.Source source;
        synchronized (transition) {
            stale = gen != epoch;
            source = telemetrySource;
            if (!stale) {
                state.set(State.GAVE_UP);
                agent = null;
                redirectScheduled = false;
                redirectPending = false;
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
            bridge.debug(L10n.tr("upgrade.staleGiveUp", reason));
            return;
        }
        // 升级失败对玩家无感：他仍在原有的中转连接上正常游戏，
        // 所以只记日志，不去打扰他。
        bridge.info(L10n.tr("upgrade.gaveUp", reason));
        observe(QualitySummary.of(QualitySummary.Path.UPGRADE,
                QualitySummary.Stage.ROUND_FINISHED, QualitySummary.Outcome.FAILED)
                .withSource(source)
                .withFailure(failureStage, failureCode));
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
                    ? L10n.tr("chat.adopted.rtt", ready.rttMs())
                    : L10n.tr("chat.adopted");
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
            bridge.debug(L10n.tr("upgrade.reportSent", report));
        } catch (RuntimeException e) {
            // 回执是尽力而为的诊断信息，失败绝不能影响升级流程
            bridge.debug(L10n.tr("upgrade.reportFailed", e));
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

    /** 平台层确认我们发起的回环重连已经建立。 */
    public void onRedirectLanded() {
        QualitySummary.Source source;
        synchronized (transition) {
            if (!redirectPending) {
                return;
            }
            redirectPending = false;
            source = telemetrySource;
        }
        observe(QualitySummary.of(QualitySummary.Path.UPGRADE,
                QualitySummary.Stage.REDIRECT_LANDED, QualitySummary.Outcome.SUCCESS)
                .withSource(source));
    }

    /** 平台层建立了别的连接，说明预期的回环重连没有落地。 */
    public void onRedirectNotLanded() {
        QualitySummary.Source source;
        synchronized (transition) {
            if (!redirectPending) {
                return;
            }
            redirectPending = false;
            source = telemetrySource;
        }
        observe(QualitySummary.of(QualitySummary.Path.UPGRADE,
                QualitySummary.Stage.ROUND_FINISHED, QualitySummary.Outcome.FAILED)
                .withSource(source)
                .withFailure(QualitySummary.FailureStage.REDIRECT,
                        QualitySummary.FailureCode.REDIRECT_FAILED));
    }

    /**
     * 平台层在 bridge tracker 尚未来得及立起的极窄窗口内，也可据此识别
     * “旧连接因切换而断开”。只表示重定向已经提交，不能当作落地成功。
     */
    public boolean redirectInProgress() {
        synchronized (transition) {
            return redirectPending;
        }
    }

    /**
     * 彻底停止并复位，游戏退出或玩家切换服务器时调用。
     *
     * <p>只管自己起的 agent——预热隧道归 {@link WarmupController}，
     * 它要活到游戏进程结束，承载服务器列表里的直连条目。
     */
    public void shutdown() {
        AgentProcess proc;
        boolean interrupted;
        QualitySummary.Source source;
        synchronized (transition) {
            // 换代：还在跑的旧 worker 之后的任何状态转移都会被拒
            epoch++;
            proc = agent;
            interrupted = state.get() == State.PUNCHING
                    || redirectScheduled || redirectPending;
            source = telemetrySource;
            agent = null;
            activeKey = null;
            activeCredentials = null;
            punchWaitBoundMs = 0;
            lastReady = null;
            upgradeReported = false;
            adoptedDirect = false;
            redirectScheduled = false;
            redirectPending = false;
            telemetrySource = QualitySummary.Source.UNKNOWN;
            // telemetryNat 刻意保留：NAT 是宿主网络属性，不随会话复位
            telemetryBackend = QualitySummary.Backend.UNKNOWN;
            state.set(State.IDLE);
        }
        // close 最长阻塞 3 秒，不能占着转移锁
        if (proc != null) {
            proc.close();
        }
        if (interrupted) {
            observe(QualitySummary.of(QualitySummary.Path.UPGRADE,
                    QualitySummary.Stage.ROUND_FINISHED, QualitySummary.Outcome.INTERRUPTED)
                    .withSource(source)
                    .withFailure(QualitySummary.FailureStage.INTERNAL,
                            QualitySummary.FailureCode.INTERRUPTED));
        }
    }

    private boolean isCurrent(long gen) {
        synchronized (transition) {
            return gen == epoch;
        }
    }

    /** 冷启动路径一旦确定就固定 source，覆盖 STARTING 前的失败。 */
    private boolean selectColdAgent(long gen) {
        synchronized (transition) {
            if (gen != epoch || state.get() != State.PUNCHING) {
                return false;
            }
            telemetrySource = QualitySummary.Source.COLD_AGENT;
            return true;
        }
    }

    /** 游戏线程执行排队任务时再验代，过期任务不产生任何副作用。 */
    private boolean markRedirectStarted(long gen, QualitySummary.Source source) {
        synchronized (transition) {
            if (gen != epoch || state.get() != State.UPGRADED || !redirectScheduled) {
                return false;
            }
            redirectScheduled = false;
            redirectPending = true;
            telemetrySource = source;
        }
        observe(QualitySummary.of(QualitySummary.Path.UPGRADE,
                QualitySummary.Stage.REDIRECT_STARTED, QualitySummary.Outcome.STARTED)
                .withSource(source));
        return true;
    }

    /** 游戏线程上的重定向提交；平台实现抛错时也必须把 pending 状态收口。 */
    private void runRedirect(long gen, QualitySummary.Source source, String message, int port) {
        if (!markRedirectStarted(gen, source)) {
            bridge.debug(L10n.tr("upgrade.staleRedirect"));
            return;
        }
        try {
            bridge.notifyPlayer(message);
            bridge.connectTo("127.0.0.1", port);
        } catch (RuntimeException e) {
            bridge.warn(L10n.tr("upgrade.redirectFailed"), e);
            onRedirectNotLanded();
            shutdown();
        }
    }

    /** agent 事件带 NAT 分类时记住它；unknown 不覆盖已知值。 */
    private void rememberNat(AgentEvent event) {
        QualitySummary.Nat nat = QualitySummary.Nat.fromWire(event.nat());
        if (nat != QualitySummary.Nat.UNKNOWN) {
            telemetryNat = nat;
        }
    }

    private void observe(QualitySummary summary) {
        try {
            // backend/nat 在这里统一盖章，发射点只关心漏斗语义
            quality.record(summary.withBackend(telemetryBackend).withNat(telemetryNat));
        } catch (RuntimeException ignored) {
            // 遥测回调绝不能影响直连状态机。
        }
    }
}
