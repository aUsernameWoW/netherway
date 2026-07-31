package cn.ripplecraft.xtcpinmc.core;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动期预热：游戏加载时就用缓存凭证提前打洞，玩家在服务器列表里
 * 可以直接选直连条目进服，不必先经中转等凭证下发。
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

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile Ready ready;
    private volatile AgentProcess agent;

    public WarmupController(ClientBridge bridge, CredentialCache cache, Timings timings,
                            Listener listener, int bindPort) {
        this.bridge = bridge;
        this.cache = cache;
        this.timings = timings == null ? Timings.defaults() : timings.normalized();
        this.listener = listener;
        this.bindPort = bindPort;
    }

    /**
     * 在后台线程开始预热；没有缓存凭证时安静地什么都不做。
     * 可从 FML 加载期调用——全程不碰调用方线程。重复调用只生效一次。
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runWarmup();
            }
        }, "xtcpinmc-warmup");
        worker.setDaemon(true);
        worker.start();
    }

    private void runWarmup() {
        AgentProcess proc = null;
        try {
            final Credentials cred = cache.loadMostRecent();
            if (cred == null) {
                bridge.debug("没有缓存凭证，跳过预热（首次进服后会自动缓存）");
                return;
            }
            Platform platform = Platform.detect();
            bridge.info("发现房间 " + cred.room() + " 的缓存凭证，开始预热直连"
                    + "（" + cred.backendId() + "，平台 " + platform + "）");

            Path cacheDir = bridge.cacheDirectory();
            Path exe = new BinaryStore(cacheDir, platform).ensureExtracted();
            // 与升级流程的 tunnel.log 分开：预热还没出结果时玩家就经中转
            // 进服的话，两个 agent 会同时在跑，共用一个日志文件会互相踩踏
            Path agentLog = cacheDir.resolve("tunnel-warmup.log");
            bridge.debug("启动预热 agent: " + AgentProcess.describeCommand(
                    AgentProcess.buildCommand(exe, cred, timings, agentLog, bindPort)));

            proc = AgentProcess.start(exe, cred, timings, cacheDir, agentLog, bindPort,
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

            AgentEvent outcome = proc.awaitOutcome(timings.outcomeWaitMs());
            if (outcome != null && outcome.type() == AgentEvent.Type.READY) {
                ready = new Ready(cred, outcome, proc);
                bridge.info("预热直连就绪，端口 " + outcome.port() + "，延迟 "
                        + outcome.rttMs() + "ms——服务器列表里的直连条目可用");
                return;
            }

            // 失败安静收场：玩家照常走中转进服；凭证若已轮换，登录后会拿到
            // 新凭证覆盖缓存，下次启动预热自然恢复。这里绝不碰升级状态机。
            String why = outcome == null ? "等待打洞结果超时"
                    : (outcome.reason() == null ? "打洞未成功" : outcome.reason());
            agent = null;
            proc.close();
            bridge.info("预热未成功（" + why + "）——不影响正常游戏，进服后仍会按既有流程尝试直连");
        } catch (Platform.UnsupportedPlatformException e) {
            bridge.debug("当前系统不支持直连，跳过预热: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            agent = null;
            closeQuietly(proc);
        } catch (Exception e) {
            agent = null;
            closeQuietly(proc);
            bridge.warn("预热直连失败（不影响正常游戏）", e);
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

    /** 彻底停止预热隧道。正常游戏退出不需要调用（shutdown hook 兜底）。 */
    public void shutdown() {
        ready = null;
        AgentProcess proc = agent;
        agent = null;
        if (proc != null) {
            proc.close();
        }
    }

    private static void closeQuietly(AgentProcess proc) {
        if (proc != null) {
            proc.close();
        }
    }

    /** 仅测试：注入就绪状态，免得真起进程。 */
    void injectReadyForTest(Credentials cred, AgentEvent event) {
        ready = new Ready(cred, event, null);
    }
}
