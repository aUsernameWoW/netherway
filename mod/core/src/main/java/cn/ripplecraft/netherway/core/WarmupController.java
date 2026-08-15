package cn.ripplecraft.netherway.core;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import cn.ripplecraft.netherway.core.telemetry.QualityObserver;
import cn.ripplecraft.netherway.core.telemetry.QualitySummary;
import cn.ripplecraft.netherway.core.telemetry.QualityWindow;

/**
 * 启动期多服务预热：每份缓存/预取凭证对应一条独立的本地隧道，
 * 平台层再为它维护一个直连条目。一个客户端因此可以同时保持多个
 * 已建立的 P2P 通路，玩家选哪个条目就走哪条。
 *
 * <p><b>只串行打洞，不串行已建立的隧道。</b>同一 NAT 上同时打多个洞会
 * 互相干扰，所以管理线程逐个建联；某条一旦 READY，其 agent 就独立
 * 守望，管理线程继续去建下一条。进服后的升级打洞也经同一把门闩
 * 与预热互斥，且优先于下一条后台预热。
 *
 * <p>这个类只管“凭证 → 本地 TCP 端口”。它不选服务器、不排名、
 * 不判断玩家应该去哪；多服务器只是多份建联状态并存。
 */
public final class WarmupController {

    /** 升级侧的观察口，用来让后台预热给玩家当前连接让路。 */
    public interface UpgradeGate {
        boolean punching();

        long punchWaitBoundMs();
    }

    /** 平台层关心的时刻。回调在后台线程触发，实现自行转到游戏主线程。 */
    public interface Listener {
        void onTunnelStarting(Credentials cred, int port);

        /** 隧道已经通过探测，可以承载 Minecraft 连接。 */
        default void onTunnelReady(Credentials cred, AgentEvent event) {
        }

        /** 曾经 READY 的隧道不再可用；旧通知可能晚于同入口的新隧道。 */
        default void onTunnelClosed(Credentials cred, int port) {
        }
    }

    /** 就绪隧道的完整快照。 */
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

    /** 一个服务入口的预热状态；字段由 roomsLock 保护。 */
    private static final class RoomState {
        Credentials cred;
        Ready ready;
        AgentProcess agent;
        int backoffAttempt;
        long nextAttemptAt;
        boolean addressWarningLogged;
        final QualityWindow qualityWindow = new QualityWindow(QualitySummary.Path.WARMUP,
                QualitySummary.Source.COLD_AGENT);

        RoomState(Credentials cred) {
            this.cred = cred;
        }
    }

    private final ClientBridge bridge;
    private final CredentialCache cache;
    private final Timings timings;
    private final Listener listener;
    /** 首条隧道期望的本地端口；后续隧道撞端口时 agent 自动改用空闲端口。 */
    private final int bindPort;
    private final Prefetcher prefetcher;
    private final QualityObserver quality;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Object roomsLock = new Object();
    private final Map<String, RoomState> rooms = new LinkedHashMap<String, RoomState>();

    /** 预热/升级打洞共用的互斥门闩。 */
    private final Object punchLock = new Object();
    private boolean warmPunching;
    private boolean upgradeWaiting;
    private boolean upgradeReserved;
    private long punchWaitMs;

    private volatile boolean stopped;
    private volatile Thread worker;
    private volatile UpgradeGate upgradeGate;
    /** agent 最近探测出的 NAT 形态；宿主网络属性，各房间共享。 */
    private volatile QualitySummary.Nat lastNat = QualitySummary.Nat.UNKNOWN;

    public WarmupController(ClientBridge bridge, CredentialCache cache, Timings timings,
                            Listener listener, int bindPort, Prefetcher prefetcher) {
        this(bridge, cache, timings, listener, bindPort, prefetcher, QualityObserver.NOOP);
    }

    public WarmupController(ClientBridge bridge, CredentialCache cache, Timings timings,
                            Listener listener, int bindPort, Prefetcher prefetcher,
                            QualityObserver quality) {
        this.bridge = bridge;
        this.cache = cache;
        this.timings = timings == null ? Timings.defaults() : timings.normalized();
        this.listener = listener;
        this.bindPort = bindPort;
        this.prefetcher = prefetcher;
        this.quality = quality == null ? QualityObserver.NOOP : quality;
    }

    /** 在后台启动多服务预热管理器。重复调用只生效一次。 */
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
        if (bridge == null || cache == null) {
            return;
        }
        Platform platform;
        try {
            platform = Platform.detect();
        } catch (Platform.UnsupportedPlatformException e) {
            bridge.debug("当前系统不支持直连，跳过预热: " + e.getMessage());
            observe(QualitySummary.of(QualitySummary.Path.WARMUP,
                    QualitySummary.Stage.ROUND_FINISHED, QualitySummary.Outcome.FAILED)
                    .withFailure(QualitySummary.FailureStage.PLATFORM,
                            QualitySummary.FailureCode.PLATFORM_UNSUPPORTED));
            return;
        }
        Path cacheDir = bridge.cacheDirectory();
        Path exe;
        try {
            exe = new BinaryStore(cacheDir, platform).ensureExtracted();
        } catch (Exception e) {
            bridge.warn("释放 agent 二进制失败，本会话预热不可用（不影响正常游戏）", e);
            observe(QualitySummary.of(QualitySummary.Path.WARMUP,
                    QualitySummary.Stage.ROUND_FINISHED, QualitySummary.Outcome.FAILED)
                    .withFailure(QualitySummary.FailureStage.EXTRACT,
                            QualitySummary.FailureCode.BINARY_EXTRACT_FAILED));
            return;
        }

        long nextPrefetchAt = 0L;
        boolean loggedIdle = false;
        while (!stopped) {
            try {
                long now = System.currentTimeMillis();
                if (prefetcher != null && now >= nextPrefetchAt) {
                    prefetcher.refresh(cache);
                }

                List<Credentials> cached = cache.loadAll();
                reconcile(cached);
                boolean empty;
                synchronized (roomsLock) {
                    empty = rooms.isEmpty();
                }
                if (prefetcher != null && now >= nextPrefetchAt) {
                    // 无凭证时快些重试；已有服务时只周期性刷新，用来发现密钥轮换。
                    nextPrefetchAt = System.currentTimeMillis()
                            + timings.warmupRetryDelayMs(empty ? 0 : Integer.MAX_VALUE);
                }

                if (empty) {
                    if (prefetcher == null) {
                        bridge.debug("没有缓存凭证，也没有可预取的服务器地址，跳过预热");
                        return;
                    }
                    if (!loggedIdle) {
                        bridge.info("暂无可用凭证，将继续预取（不影响普通连接）");
                        loggedIdle = true;
                    }
                } else {
                    loggedIdle = false;
                    runDueAttempts(exe, cacheDir);
                }

                Thread.sleep(managerPollMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                bridge.warn("预热管理器一轮异常（继续运行，不影响正常游戏）", e);
                try {
                    Thread.sleep(timings.warmupRetryDelayMs(0));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 把缓存当作希望集合：新增目标建状态，参数轮换时重建对应隧道。 */
    private void reconcile(List<Credentials> cached) {
        Map<String, Credentials> desired = new LinkedHashMap<String, Credentials>();
        for (Credentials cred : cached) {
            desired.put(cred.dedupKey(), cred);
        }
        List<AgentProcess> close = new ArrayList<AgentProcess>();
        List<Ready> closedReady = new ArrayList<Ready>();
        synchronized (roomsLock) {
            java.util.Iterator<Map.Entry<String, RoomState>> it = rooms.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, RoomState> entry = it.next();
                if (!desired.containsKey(entry.getKey())) {
                    if (entry.getValue().agent != null) {
                        close.add(entry.getValue().agent);
                    }
                    if (entry.getValue().ready != null) {
                        closedReady.add(entry.getValue().ready);
                    }
                    it.remove();
                }
            }
            for (Map.Entry<String, Credentials> entry : desired.entrySet()) {
                RoomState room = rooms.get(entry.getKey());
                if (room == null) {
                    rooms.put(entry.getKey(), new RoomState(entry.getValue()));
                    continue;
                }
                Credentials previous = room.cred;
                room.cred = entry.getValue();
                if (!sameConnectionSettings(previous, room.cred)) {
                    if (room.agent != null) {
                        close.add(room.agent);
                    }
                    if (room.ready != null) {
                        closedReady.add(room.ready);
                    }
                    room.agent = null;
                    room.ready = null;
                    room.backoffAttempt = 0;
                    room.nextAttemptAt = 0L;
                    room.addressWarningLogged = false;
                    bridge.info("房间 " + room.cred.room() + " 的建联参数已更新，将重建预热隧道");
                }
            }
        }
        notifyClosed(closedReady);
        closeAll(close);
    }

    /** 每环只串行执行到期尝试；READY 的 agent 不阻塞后续服务。 */
    private void runDueAttempts(Path exe, Path cacheDir) throws InterruptedException {
        List<RoomState> snapshot;
        synchronized (roomsLock) {
            snapshot = new ArrayList<RoomState>(rooms.values());
        }
        for (RoomState room : snapshot) {
            if (stopped) {
                return;
            }
            long now = System.currentTimeMillis();
            Ready ready;
            synchronized (roomsLock) {
                ready = room.ready;
            }
            if (ready != null) {
                if (alive(ready)) {
                    continue;
                }
                synchronized (roomsLock) {
                    if (room.ready == ready) {
                        room.ready = null;
                        room.agent = null;
                        room.backoffAttempt = 0;
                        room.nextAttemptAt = 0L;
                    }
                }
                if (ready.proc != null) {
                    ready.proc.close();
                }
                notifyClosed(ready);
                observe(room.cred, room.qualityWindow.failed(QualitySummary.Stage.TUNNEL_LOST,
                        QualitySummary.FailureStage.BACKEND,
                        QualitySummary.FailureCode.BACKEND_EXITED));
                bridge.info("房间 " + room.cred.room() + " 的预热隧道已退出，将重新建立");
            }

            Credentials cred;
            synchronized (roomsLock) {
                cred = room.cred;
                if (room.ready != null || now < room.nextAttemptAt) {
                    continue;
                }
                if (cred.needsRendezvousAddress()) {
                    if (!room.addressWarningLogged) {
                        bridge.info("房间 " + cred.room()
                                + " 的旧缓存未带服务入口，无法预热（预取或进服一次后自愈）");
                        room.addressWarningLogged = true;
                    }
                    scheduleRetry(room);
                    continue;
                }
            }

            if (!acquireWarmPunch(cred)) {
                return;
            }
            boolean becameReady = false;
            try {
                becameReady = runAttempt(room, cred, exe, cacheDir);
            } catch (Exception e) {
                bridge.warn("房间 " + cred.room() + " 的预热尝试异常（继续重试）", e);
            } finally {
                releaseWarmPunch();
            }
            synchronized (roomsLock) {
                if (becameReady) {
                    room.backoffAttempt = 0;
                    room.nextAttemptAt = Long.MAX_VALUE;
                } else if (room.ready == null) {
                    scheduleRetry(room);
                }
            }
            // 玩家进服的升级请求优先于下一个后台目标。
            if (upgradeBusy()) {
                return;
            }
        }
    }

    private boolean runAttempt(final RoomState room, final Credentials cred,
                               Path exe, Path cacheDir) throws Exception {
        final Path agentLog = cacheDir.resolve("tunnel-warmup-" + shortId(cred.dedupKey()) + ".log");
        bridge.info("用房间 " + cred.room() + " 的凭证预热直连（" + cred.backendId() + "）");
        bridge.debug("启动预热 agent: " + AgentProcess.describeCommand(
                AgentProcess.buildCommand(exe, cred, timings, agentLog, bindPort)));

        long waitMs = timings.outcomeWaitMs(cred.punchTimeoutMs());
        AgentProcess proc = null;
        boolean retained = false;
        try {
            try {
                proc = AgentProcess.start(exe, cred, timings, cacheDir, agentLog, bindPort,
                        new AgentProcess.Listener() {
                            @Override
                            public void onEvent(AgentEvent event) {
                                bridge.debug("房间 " + cred.room() + " 的预热 agent 事件: " + event);
                                if (event.type() == AgentEvent.Type.STARTING) {
                                    room.qualityWindow.markAttempts(1);
                                    if (event.port() > 0 && listener != null) {
                                        listener.onTunnelStarting(cred, event.port());
                                    }
                                }
                            }

                            @Override
                            public void onStderrLine(String line) {
                                bridge.debug("房间 " + cred.room() + " 的预热 agent: " + line);
                            }
                        });
            } catch (java.io.IOException e) {
                observe(cred, room.qualityWindow.failed(QualitySummary.Stage.ROUND_FINISHED,
                        QualitySummary.FailureStage.START,
                        QualitySummary.FailureCode.AGENT_START_FAILED));
                throw e;
            } catch (RuntimeException e) {
                observe(cred, room.qualityWindow.failed(QualitySummary.Stage.ROUND_FINISHED,
                        QualitySummary.FailureStage.START,
                        QualitySummary.FailureCode.AGENT_START_FAILED));
                throw e;
            }

            AgentEvent outcome = proc.awaitOutcome(waitMs);
            if (outcome != null) {
                rememberNat(outcome);
            }
            if (outcome != null && outcome.type() == AgentEvent.Type.READY) {
                synchronized (roomsLock) {
                    if (stopped || rooms.get(cred.dedupKey()) != room) {
                        return false;
                    }
                    room.agent = proc;
                    room.ready = new Ready(room.cred, outcome, proc);
                }
                retained = true;
                bridge.info("房间 " + cred.room() + " 的预热直连就绪，端口 "
                        + outcome.port() + "，延迟 " + outcome.rttMs() + "ms");
                if (listener != null) {
                    listener.onTunnelReady(room.cred, outcome);
                }
                observe(cred, room.qualityWindow.succeeded(QualitySummary.Stage.TUNNEL_READY,
                        outcome.elapsedMs(), outcome.rttMs()));
                return true;
            }

            String why = outcome == null ? "等待打洞结果超时"
                    : (outcome.reason() == null ? "打洞未成功" : outcome.reason());
            bridge.info("房间 " + cred.room() + " 的预热未成功（" + why
                    + "）——将按退避重试，不影响普通连接");
            if (outcome == null) {
                observe(cred, room.qualityWindow.failed(QualitySummary.Stage.ROUND_FINISHED,
                        QualitySummary.FailureStage.PROBE,
                        QualitySummary.FailureCode.READY_PROBE_TIMEOUT));
            } else {
                observe(cred, room.qualityWindow.failed(QualitySummary.Stage.ROUND_FINISHED,
                        QualitySummary.FailureStage.fromWire(outcome.failureStage()),
                        QualitySummary.FailureCode.fromWire(outcome.failureCode())));
            }
            return false;
        } finally {
            if (!retained && proc != null) {
                proc.close();
            }
        }
    }

    private void scheduleRetry(RoomState room) {
        long delay = timings.warmupRetryDelayMs(room.backoffAttempt++);
        room.nextAttemptAt = System.currentTimeMillis() + delay;
        bridge.debug("房间 " + room.cred.room() + " 的预热将在 "
                + (delay / 1000) + " 秒后重试");
    }

    private long managerPollMs() {
        return Math.max(100L, Math.min(1000L, timings.probeIntervalMs()));
    }

    /**
     * 忽略每次预认证都会续签的玩家令牌；其余 backend 参数任一变化
     * 都视为建联目标已更新。core 不解释这些参数的具体意义。
     */
    private static boolean sameConnectionSettings(Credentials a, Credentials b) {
        if (a == null || b == null || !a.backendId().equals(b.backendId())
                || !a.dedupKey().equals(b.dedupKey())) {
            return false;
        }
        Map<String, String> ap = new LinkedHashMap<String, String>(a.params());
        Map<String, String> bp = new LinkedHashMap<String, String>(b.params());
        ap.remove(Credentials.PARAM_USER);
        ap.remove(Credentials.PARAM_USER_TOKEN);
        bp.remove(Credentials.PARAM_USER);
        bp.remove(Credentials.PARAM_USER_TOKEN);
        return ap.equals(bp);
    }

    // ---------- 打洞互斥 ----------

    private boolean acquireWarmPunch(Credentials cred) throws InterruptedException {
        synchronized (punchLock) {
            while (!stopped) {
                if (!upgradeWaiting && !upgradeReserved && !upgradeBusy()) {
                    warmPunching = true;
                    punchWaitMs = timings.outcomeWaitMs(cred.punchTimeoutMs());
                    return true;
                }
                punchLock.wait(managerPollMs());
            }
            return false;
        }
    }

    private void releaseWarmPunch() {
        synchronized (punchLock) {
            warmPunching = false;
            punchWaitMs = 0L;
            punchLock.notifyAll();
        }
    }

    /**
     * 升级 worker 在起冷 agent 前预留打洞权。方法会等当前预热尝试结束，
     * 并阻止管理器抢先开始下一个服务。返回 false 表示预热已彻底停止，
     * 升级可照常独立进行。
     */
    boolean reserveUpgradePunch() {
        synchronized (punchLock) {
            upgradeWaiting = true;
            try {
                while (warmPunching && !stopped) {
                    try {
                        punchLock.wait(managerPollMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                if (stopped) {
                    return false;
                }
                upgradeReserved = true;
                return true;
            } finally {
                upgradeWaiting = false;
                punchLock.notifyAll();
            }
        }
    }

    void releaseUpgradePunch() {
        synchronized (punchLock) {
            upgradeReserved = false;
            punchLock.notifyAll();
        }
    }

    public boolean punching() {
        synchronized (punchLock) {
            return warmPunching;
        }
    }

    public long punchWaitBoundMs() {
        synchronized (punchLock) {
            return punchWaitMs > 0 ? punchWaitMs : timings.outcomeWaitMs();
        }
    }

    public void setUpgradeGate(UpgradeGate gate) {
        this.upgradeGate = gate;
        synchronized (punchLock) {
            punchLock.notifyAll();
        }
    }

    private boolean upgradeBusy() {
        UpgradeGate gate = upgradeGate;
        return gate != null && gate.punching();
    }

    // ---------- 就绪查询 ----------

    public Integer readyPort(String dedupKey) {
        synchronized (roomsLock) {
            RoomState room = rooms.get(dedupKey);
            return room == null || room.ready == null || !alive(room.ready)
                    ? null : Integer.valueOf(room.ready.event.port());
        }
    }

    public AgentEvent readyEvent(String dedupKey) {
        synchronized (roomsLock) {
            RoomState room = rooms.get(dedupKey);
            return room == null || room.ready == null || !alive(room.ready)
                    ? null : room.ready.event;
        }
    }

    /** 按本地端口反查就绪隧道，用于采认玩家从某个直连条目进服。 */
    public Credentials credentialsForPort(int port) {
        synchronized (roomsLock) {
            for (RoomState room : rooms.values()) {
                if (room.ready != null && room.ready.event.port() == port && alive(room.ready)) {
                    return room.cred;
                }
            }
            return null;
        }
    }

    private static boolean alive(Ready ready) {
        return ready.proc == null || ready.proc.isAlive();
    }

    /** 彻底停止管理器与所有预热隧道。 */
    public void shutdown() {
        stopped = true;
        Thread w = worker;
        worker = null;
        if (w != null) {
            w.interrupt();
        }
        synchronized (punchLock) {
            punchLock.notifyAll();
        }
        List<AgentProcess> close = new ArrayList<AgentProcess>();
        List<Ready> closedReady = new ArrayList<Ready>();
        synchronized (roomsLock) {
            for (RoomState room : rooms.values()) {
                if (room.agent != null) {
                    close.add(room.agent);
                }
                if (room.ready != null) {
                    closedReady.add(room.ready);
                }
            }
            rooms.clear();
        }
        notifyClosed(closedReady);
        closeAll(close);
    }

    /** 仅测试：可重复注入多个就绪服务，免得真起进程。 */
    void injectReadyForTest(Credentials cred, AgentEvent event) {
        Ready previous;
        synchronized (roomsLock) {
            RoomState room = rooms.get(cred.dedupKey());
            if (room == null) {
                room = new RoomState(cred);
                rooms.put(cred.dedupKey(), room);
            }
            previous = room.ready;
            room.cred = cred;
            room.ready = new Ready(cred, event, null);
        }
        notifyClosed(previous);
        if (listener != null) {
            listener.onTunnelReady(cred, event);
        }
    }

    /** agent 事件带 NAT 分类时记住它；unknown 不覆盖已知值。 */
    private void rememberNat(AgentEvent event) {
        QualitySummary.Nat nat = QualitySummary.Nat.fromWire(event.nat());
        if (nat != QualitySummary.Nat.UNKNOWN) {
            lastNat = nat;
        }
    }

    /** 有房间上下文的摘要在这里盖上 backend/nat 维度。 */
    private void observe(Credentials cred, QualitySummary summary) {
        if (summary == null) {
            return;
        }
        observe(summary
                .withBackend(QualitySummary.Backend.fromBackendId(cred.backendId()))
                .withNat(lastNat));
    }

    private void observe(QualitySummary summary) {
        if (summary == null) {
            return;
        }
        try {
            quality.record(summary);
        } catch (RuntimeException ignored) {
            // 遥测不影响建联。
        }
    }

    private static void closeAll(List<AgentProcess> processes) {
        for (AgentProcess proc : processes) {
            if (proc != null) {
                proc.close();
            }
        }
    }

    private void notifyClosed(List<Ready> ready) {
        for (Ready item : ready) {
            notifyClosed(item);
        }
    }

    private void notifyClosed(Ready ready) {
        if (listener != null && ready != null) {
            listener.onTunnelClosed(ready.cred, ready.event.port());
        }
    }

    private static String shortId(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(Charset.forName("UTF-8")));
            StringBuilder out = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                out.append(String.format("%02x", hash[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            // Java 实现必须提供 SHA-256。
            throw new IllegalStateException(e);
        }
    }
}
