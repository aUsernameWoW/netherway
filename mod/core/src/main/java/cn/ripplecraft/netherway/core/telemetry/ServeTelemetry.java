package cn.ripplecraft.netherway.core.telemetry;

/**
 * Telemetry state machine for the serve process lifecycle (dedicated server
 * side, path = serve).
 *
 * <p>serve has no stdout JSON contract, so there are only four observable
 * moments: start attempt, ready, start failure, process exit. "Ready" is
 * recognised from the process output through a language-independent marker
 * contract: {@code cmd/netherway/serve_gonc.go} prefixes its ready line with
 * {@code [serve-ready]} once a signaling broker has been reached, and
 * warning-level lines with {@code [serve-warn]} (the platform log pump
 * escalates those to WARN). The rest of each line is localised and must not
 * be matched. Both literals are pinned on the Go side by
 * {@code TestServeMarkers} and here by SelfTest.
 *
 * <p>No I/O and no platform types: the platform layer (ServerAgent) feeds the
 * moments in and summaries leave through {@link QualityObserver}. Methods are
 * synchronized because the start thread and the log pump thread race.
 */
public final class ServeTelemetry {

    /**
     * Prefix of the serve "ready" line. Mirrors Go
     * {@code cmd/netherway/serve_gonc.go} {@code ServeReadyMarker} byte for byte.
     */
    public static final String GONC_READY_MARKER = "[serve-ready]";

    /**
     * Prefix of serve warning-level lines. Mirrors Go
     * {@code cmd/netherway/serve_gonc.go} {@code ServeWarnMarker} byte for byte.
     */
    public static final String GONC_WARN_MARKER = "[serve-warn]";

    private enum State { IDLE, STARTING, READY, DONE }

    private final QualityObserver quality;
    private final QualitySummary.Backend backend;

    private State state = State.IDLE;
    private long startedAtMs;

    public ServeTelemetry(QualityObserver quality, QualitySummary.Backend backend) {
        this.quality = quality == null ? QualityObserver.NOOP : quality;
        this.backend = backend == null ? QualitySummary.Backend.UNKNOWN : backend;
    }

    /** serve 即将尝试启动（含随后可能失败的准备工作）。 */
    public synchronized void onStartAttempt() {
        if (state != State.IDLE) {
            return;
        }
        state = State.STARTING;
        startedAtMs = System.currentTimeMillis();
        observe(QualitySummary.of(QualitySummary.Path.SERVE,
                QualitySummary.Stage.STARTED, QualitySummary.Outcome.STARTED));
    }

    /** 启动没走到进程活着的程度（平台/释放/spawn 失败、backend 不支持）。 */
    public synchronized void onStartFailure(QualitySummary.FailureStage failureStage,
                                            QualitySummary.FailureCode failureCode) {
        if (state != State.STARTING) {
            return;
        }
        state = State.DONE;
        observe(QualitySummary.of(QualitySummary.Path.SERVE,
                QualitySummary.Stage.ROUND_FINISHED, QualitySummary.Outcome.FAILED)
                .withFailure(failureStage, failureCode));
    }

    /** Every serve output line; the first {@link #GONC_READY_MARKER} line records TUNNEL_READY. */
    public synchronized void onLogLine(String line) {
        if (state != State.STARTING || line == null || !line.contains(GONC_READY_MARKER)) {
            return;
        }
        state = State.READY;
        observe(QualitySummary.of(QualitySummary.Path.SERVE,
                QualitySummary.Stage.TUNNEL_READY, QualitySummary.Outcome.SUCCESS)
                .withTimings(Math.max(0L, System.currentTimeMillis() - startedAtMs), 0L));
    }

    /**
     * serve 进程退出。
     *
     * @param deliberate 是否我们自己停的（服务端关闭）；主动停不算事故
     */
    public synchronized void onExit(boolean deliberate) {
        State was = state;
        state = State.DONE;
        if (deliberate) {
            return;
        }
        if (was == State.READY) {
            // 就绪后死掉：房间已经没人发布，玩家的打洞会开始失败
            observe(QualitySummary.of(QualitySummary.Path.SERVE,
                    QualitySummary.Stage.TUNNEL_LOST, QualitySummary.Outcome.FAILED)
                    .withFailure(QualitySummary.FailureStage.BACKEND,
                            QualitySummary.FailureCode.BACKEND_EXITED));
        } else if (was == State.STARTING) {
            // 进程起来了但没等到就绪就退了，通常是配置错误
            observe(QualitySummary.of(QualitySummary.Path.SERVE,
                    QualitySummary.Stage.ROUND_FINISHED, QualitySummary.Outcome.FAILED)
                    .withFailure(QualitySummary.FailureStage.START,
                            QualitySummary.FailureCode.AGENT_EARLY_EXIT));
        }
    }

    private void observe(QualitySummary summary) {
        try {
            quality.record(summary.withBackend(backend));
        } catch (RuntimeException ignored) {
            // 遥测绝不影响 serve 本体。
        }
    }
}
