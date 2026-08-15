package cn.ripplecraft.netherway.core.telemetry;

/**
 * serve 进程生命周期的遥测状态机（专用服务器侧，path = serve）。
 *
 * <p>serve 没有 stdout JSON 契约，可观察时刻只有四个：尝试启动、注册成功、
 * 启动失败、进程退出。注册成功靠 frp 的日志原文 {@code start proxy success}
 * 识别——CI 冒烟已把这行钉为 frp bump 的绊线（见 build.yml），这里与之同源，
 * frp 若改字样两处一起改。
 *
 * <p>本类不做 I/O、不依赖平台类型，平台层（ServerAgent）只负责把时刻喂进来；
 * 摘要经 {@link QualityObserver} 出去。方法同步：启动线程与日志泵线程会并发到达。
 */
public final class ServeTelemetry {

    /** frp 注册成功的日志原文；与 build.yml 冒烟的绊线保持同一字符串。 */
    private static final String PROXY_SUCCESS_MARKER = "start proxy success";

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

    /** serve 输出的每一行日志；识别到注册成功即记 TUNNEL_READY。 */
    public synchronized void onLogLine(String line) {
        if (state != State.STARTING || line == null
                || !line.contains(PROXY_SUCCESS_MARKER)) {
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
            // 注册成功后死掉：代理已经没了，玩家的打洞会开始失败
            observe(QualitySummary.of(QualitySummary.Path.SERVE,
                    QualitySummary.Stage.TUNNEL_LOST, QualitySummary.Outcome.FAILED)
                    .withFailure(QualitySummary.FailureStage.BACKEND,
                            QualitySummary.FailureCode.BACKEND_EXITED));
        } else if (was == State.STARTING) {
            // 进程起来了但没等到注册成功就退了，通常是配置错误
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
