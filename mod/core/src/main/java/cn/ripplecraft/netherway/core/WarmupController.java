package cn.ripplecraft.netherway.core;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动期预热：游戏加载时就提前打洞，玩家在服务器列表里直接选直连条目
 * 进服，不必先经中转等凭证下发。凭证来源有二：上次进服落下的缓存，以及
 * {@link Prefetcher}（配置了 authbridge 或能从服务器列表推导出来时）在
 * 每轮开始前的预取——首次启动、密钥轮换后都无需先走中转。
 *
 * <p><b>打不通就一直打。</b>预热是无限重试的循环：失败按
 * {@link Timings#warmupRetryDelayMs} 指数退避后重来，就绪后守望 agent
 * 进程，进程一死回到循环重打。刻意不做任何中转兜底——直连条目要么是
 * 真 P2P，要么 ping 不通，玩家想走中转就选列表里既有的中转条目。
 *
 * <p>与 {@link UpgradeController} 刻意分离：预热失败绝不能进它的
 * {@code GAVE_UP}——那个状态的语义是「本会话别再折腾」，会把进服后的
 * 正常升级一并锁死。预热失败就当无事发生，一切回到既有流程；两者只在
 * 两处交接：升级流程复用已就绪的预热隧道（{@link #readyPort}），
 * 平台层识别「玩家经直连条目进服」（{@link #credentialsForPort}）。
 *
 * <p>预热隧道的生命周期是整个游戏进程：断开服务器、回主菜单都不停——
 * 它承载着服务器列表里的直连条目。游戏退出时由 {@link AgentProcess}
 * 注册的 JVM shutdown hook 兜底清理，不会留孤儿进程。
 */
public final class WarmupController {

    /** 平台层关心的时刻。回调在后台线程触发，实现自行转到游戏主线程。 */
    public interface Listener {
        /**
         * agent 已选定本地端口、即将开始打洞。此刻就该更新服务器列表里的
         * 直连条目——端口每次启动都可能不同，不更新条目就指向旧端口。
         * 重试的每一轮都会触发一次，实现自行判断条目是否已是最新。
         * 打洞成败不影响条目正确性：没打通时条目 ping 不通，玩家一眼可辨。
         */
        void onTunnelStarting(Credentials cred, int port);
    }

    /** 就绪隧道的完整快照，经单个 volatile 字段发布，读到即一致。 */
    private static final class Ready {
        final Credentials cred;
        final AgentEvent event;
        final AgentProcess proc; // 测试注入时为 null

        Ready(Credentials cred, AgentEvent event, AgentProcess proc) {
            this.cred = cred;
            this.event = event;
            this.proc = proc;
        }
    }

    private final ClientBridge bridge;
    private final CredentialCache cache;
    private final Timings timings;
    private final Listener listener;
    /** 期望的本地端口；0 表示随机。被占用时 agent 自动回落到空闲端口。 */
    private final int bindPort;
    /** 每轮开始前预取凭证；null 表示没有可用的 authbridge 候选。 */
    private final Prefetcher prefetcher;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean stopped;
    private volatile Thread worker;
    private volatile Ready ready;
    private volatile AgentProcess agent;

    public WarmupController(ClientBridge bridge, CredentialCache cache, Timings timings,
                            Listener listener, int bindPort, Prefetcher prefetcher) {
        this.bridge = bridge;
        this.cache = cache;
        this.timings = timings == null ? Timings.defaults() : timings.normalized();
        this.listener = listener;
        this.bindPort = bindPort;
        this.prefetcher = prefetcher;
    }

    /**
     * 在后台线程开始预热循环；既没有缓存凭证也没有预取来源时安静退出。
     * 可从 FML 加载期调用——全程不碰调用方线程。重复调用只生效一次。
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread w = new Thread(new Runnable() {
            @Override
            public void run() {
                runWarmupLoop();
            }
        }, "netherway-warmup");
        w.setDaemon(true);
        worker = w;
        w.start();
    }

    private void runWarmupLoop() {
        Platform platform;
        try {
            platform = Platform.detect();
        } catch (Platform.UnsupportedPlatformException e) {
            bridge.debug("当前系统不支持直连，跳过预热: " + e.getMessage());
            return;
        }
        Path cacheDir = bridge.cacheDirectory();
        Path exe;
        try {
            exe = new BinaryStore(cacheDir, platform).ensureExtracted();
        } catch (Exception e) {
            bridge.warn("释放 agent 二进制失败，本会话预热不可用（不影响正常游戏）", e);
            return;
        }
        // 与升级流程的 tunnel.log 分开：预热还没出结果时玩家就经中转
        // 进服的话，两个 agent 会同时在跑，共用一个日志文件会互相踩踏
        Path agentLog = cacheDir.resolve("tunnel-warmup.log");

        int attempt = 0;
        boolean loggedIdle = false;
        while (!stopped) {
            try {
                if (prefetcher != null) {
                    // 每轮都预取：密钥轮换（如服务端重启）后这里拿到新凭证，
                    // 下一跳打洞就直接成功，玩家中途无需走任何中转
                    prefetcher.refresh(exe, cache.directory());
                }
                Credentials cred = cache.loadMostRecent();
                if (cred == null) {
                    if (prefetcher == null) {
                        bridge.debug("没有缓存凭证也没有 authbridge 候选，跳过预热"
                                + "（首次进服后会自动缓存）");
                        return;
                    }
                    if (!loggedIdle) {
                        bridge.info("暂无可用凭证（预取未成功且无缓存），将按退避周期持续重试");
                        loggedIdle = true;
                    }
                } else if (runAttempt(cred, exe, cacheDir, agentLog)) {
                    // 就绪过又退出：清零退避，尽快恢复直连条目
                    attempt = 0;
                }
                if (stopped) {
                    return;
                }
                long delay = timings.warmupRetryDelayMs(attempt++);
                bridge.debug("预热将在 " + (delay / 1000) + " 秒后重试");
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                bridge.warn("预热一轮异常（继续重试，不影响正常游戏）", e);
                try {
                    Thread.sleep(timings.warmupRetryDelayMs(attempt++));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 起一次 agent 打洞；就绪则发布状态并守望进程直到退出。
     *
     * @return 本轮是否就绪过（用于清零退避）
     */
    private boolean runAttempt(final Credentials cred, Path exe, Path cacheDir, Path agentLog)
            throws Exception {
        bridge.info("用房间 " + cred.room() + " 的凭证预热直连（" + cred.backendId() + "）");
        bridge.debug("启动预热 agent: " + AgentProcess.describeCommand(
                AgentProcess.buildCommand(exe, cred, timings, agentLog, bindPort)));

        AgentProcess proc = AgentProcess.start(exe, cred, timings, cacheDir, agentLog, bindPort,
                new AgentProcess.Listener() {
                    @Override
                    public void onEvent(AgentEvent event) {
                        bridge.debug("预热 agent 事件: " + event);
                        if (event.type() == AgentEvent.Type.STARTING
                                && event.port() > 0 && listener != null) {
                            listener.onTunnelStarting(cred, event.port());
                        }
                    }

                    @Override
                    public void onStderrLine(String line) {
                        bridge.debug("预热 agent: " + line);
                    }
                });
        agent = proc;
        try {
            AgentEvent outcome = proc.awaitOutcome(timings.outcomeWaitMs());
            if (outcome != null && outcome.type() == AgentEvent.Type.READY) {
                ready = new Ready(cred, outcome, proc);
                bridge.info("预热直连就绪，端口 " + outcome.port() + "，延迟 "
                        + outcome.rttMs() + "ms——服务器列表里的直连条目可用");
                // 守望：进程退出（掉线、frps 重启、被杀）才回到重试循环
                proc.awaitExit();
                ready = null;
                if (!stopped) {
                    bridge.info("预热隧道进程退出，将重新打洞");
                }
                return true;
            }
            // 失败安静收场：玩家照常走中转进服；凭证若已轮换，下一轮的
            // 预取或登录后的下发会带来新凭证。这里绝不碰升级状态机。
            String why = outcome == null ? "等待打洞结果超时"
                    : (outcome.reason() == null ? "打洞未成功" : outcome.reason());
            bridge.info("预热未成功（" + why + "）——继续按退避重试，不影响正常游戏");
            return false;
        } finally {
            ready = null;
            agent = null;
            proc.close();
        }
    }

    /** 指定房间的预热隧道已就绪且进程还活着时返回其本地端口，否则 null。 */
    public Integer readyPort(String dedupKey) {
        Ready r = ready;
        if (r == null || !r.cred.dedupKey().equals(dedupKey) || !alive(r)) {
            return null;
        }
        return r.event.port();
    }

    /** 指定房间就绪时的 READY 事件（采认/复用时取 rtt 与端口用）；未就绪返回 null。 */
    public AgentEvent readyEvent(String dedupKey) {
        Ready r = ready;
        if (r == null || !r.cred.dedupKey().equals(dedupKey) || !alive(r)) {
            return null;
        }
        return r.event;
    }

    /**
     * 反查：本机回环上的 port 是不是就绪的预热隧道。平台层在新连接建立时
     * 用它识别「玩家经直连条目进的服」，进而把该连接采认为已升级
     * （{@link UpgradeController#adoptDirectConnection}）。
     */
    public Credentials credentialsForPort(int port) {
        Ready r = ready;
        if (r == null || r.event.port() != port || !alive(r)) {
            return null;
        }
        return r.cred;
    }

    private static boolean alive(Ready r) {
        // 进程活着不等于隧道此刻健康（frp 的 keepTunnelOpen 会自行重试维护），
        // 但进程死了隧道必然没了——这是能拿到的最可靠的廉价判据。
        return r.proc == null || r.proc.isAlive();
    }

    /** 彻底停止预热循环与隧道。正常游戏退出不需要调用（shutdown hook 兜底）。 */
    public void shutdown() {
        stopped = true;
        ready = null;
        Thread w = worker;
        worker = null;
        if (w != null) {
            w.interrupt();
        }
        AgentProcess proc = agent;
        agent = null;
        if (proc != null) {
            proc.close();
        }
    }

    /** 仅测试：注入就绪状态，免得真起进程。 */
    void injectReadyForTest(Credentials cred, AgentEvent event) {
        ready = new Ready(cred, event, null);
    }
}
