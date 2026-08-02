package cn.ripplecraft.netherway.core;

/**
 * 客户端可调的时间参数，来自 mod 的配置文件。
 *
 * <p>默认值取自真机实测：顺利时约 5 秒完成建链（含 STUN 探测与打洞），
 * 重新打洞的慢路径会明显更久。玩家此刻已经在中转连接上正常游戏，
 * 这段等待是后台进行的，所以宁可放宽也不要误判为失败。
 *
 * <p>不要把这些值写死在调用处：不同玩家的网络差异很大，服主需要能调。
 */
public final class Timings {

    private static final long DEFAULT_PUNCH_TIMEOUT_MS = 15_000L;
    private static final long DEFAULT_PROBE_INTERVAL_MS = 250L;
    private static final long DEFAULT_PROBE_TIMEOUT_MS = 2_000L;
    private static final long DEFAULT_STARTUP_GRACE_MS = 5_000L;
    private static final long DEFAULT_WARMUP_RETRY_INITIAL_MS = 10_000L;
    private static final long DEFAULT_WARMUP_RETRY_MAX_MS = 120_000L;
    private static final long DEFAULT_PREFETCH_TIMEOUT_MS = 60_000L;

    private final long punchTimeoutMs;
    private final long probeIntervalMs;
    private final long probeTimeoutMs;
    private final long startupGraceMs;
    private final long warmupRetryInitialMs;
    private final long warmupRetryMaxMs;
    private final long prefetchTimeoutMs;

    public Timings(long punchTimeoutMs, long probeIntervalMs,
                   long probeTimeoutMs, long startupGraceMs) {
        this(punchTimeoutMs, probeIntervalMs, probeTimeoutMs, startupGraceMs,
                DEFAULT_WARMUP_RETRY_INITIAL_MS, DEFAULT_WARMUP_RETRY_MAX_MS,
                DEFAULT_PREFETCH_TIMEOUT_MS);
    }

    private Timings(long punchTimeoutMs, long probeIntervalMs,
                    long probeTimeoutMs, long startupGraceMs,
                    long warmupRetryInitialMs, long warmupRetryMaxMs,
                    long prefetchTimeoutMs) {
        this.punchTimeoutMs = punchTimeoutMs;
        this.probeIntervalMs = probeIntervalMs;
        this.probeTimeoutMs = probeTimeoutMs;
        this.startupGraceMs = startupGraceMs;
        this.warmupRetryInitialMs = warmupRetryInitialMs;
        this.warmupRetryMaxMs = warmupRetryMaxMs;
        this.prefetchTimeoutMs = prefetchTimeoutMs;
    }

    public static Timings defaults() {
        return new Timings(DEFAULT_PUNCH_TIMEOUT_MS, DEFAULT_PROBE_INTERVAL_MS,
                DEFAULT_PROBE_TIMEOUT_MS, DEFAULT_STARTUP_GRACE_MS);
    }

    /** 换掉预热重试的退避区间，其余参数不变。 */
    public Timings withWarmupRetry(long initialMs, long maxMs) {
        return new Timings(punchTimeoutMs, probeIntervalMs, probeTimeoutMs,
                startupGraceMs, initialMs, maxMs, prefetchTimeoutMs);
    }

    /** 换掉凭证预取子进程的总超时，其余参数不变。 */
    public Timings withPrefetchTimeout(long timeoutMs) {
        return new Timings(punchTimeoutMs, probeIntervalMs, probeTimeoutMs,
                startupGraceMs, warmupRetryInitialMs, warmupRetryMaxMs, timeoutMs);
    }

    /** 把非正值回填成默认值，避免配置文件写了 0 导致空转或死等。 */
    public Timings normalized() {
        return new Timings(
                punchTimeoutMs > 0 ? punchTimeoutMs : DEFAULT_PUNCH_TIMEOUT_MS,
                probeIntervalMs > 0 ? probeIntervalMs : DEFAULT_PROBE_INTERVAL_MS,
                probeTimeoutMs > 0 ? probeTimeoutMs : DEFAULT_PROBE_TIMEOUT_MS,
                startupGraceMs > 0 ? startupGraceMs : DEFAULT_STARTUP_GRACE_MS,
                warmupRetryInitialMs > 0 ? warmupRetryInitialMs : DEFAULT_WARMUP_RETRY_INITIAL_MS,
                warmupRetryMaxMs > 0 ? warmupRetryMaxMs : DEFAULT_WARMUP_RETRY_MAX_MS,
                prefetchTimeoutMs > 0 ? prefetchTimeoutMs : DEFAULT_PREFETCH_TIMEOUT_MS);
    }

    /** 打洞总超时，超时即放弃升级。 */
    public long punchTimeoutMs() {
        return punchTimeoutMs;
    }

    public long probeIntervalMs() {
        return probeIntervalMs;
    }

    public long probeTimeoutMs() {
        return probeTimeoutMs;
    }

    /**
     * 在打洞超时之外额外留给进程启动、二进制释放的余量。
     *
     * <p>等待终态的总时长应当比 agent 自己的超时更长，否则 mod 会先于
     * agent 判定失败，白白丢掉一次本可成功的升级。
     */
    public long startupGraceMs() {
        return startupGraceMs;
    }

    /** mod 等待终态的总时长。 */
    public long outcomeWaitMs() {
        return punchTimeoutMs + startupGraceMs;
    }

    /**
     * 预热第 {@code attempt} 次失败后的重试等待（attempt 从 0 起）：
     * 指数退避，封顶 {@link #warmupRetryMaxMs}。「打不通就一直打」的
     * 节流全在这里——失败的打洞本身要花十几秒，叠加退避后稳态大约
     * 每两三分钟一轮，对 frps/STUN/服务器都只是零星流量。
     */
    public long warmupRetryDelayMs(int attempt) {
        long d = warmupRetryInitialMs;
        for (int i = 0; i < attempt && d < warmupRetryMaxMs; i++) {
            d *= 2;
        }
        return Math.min(d, warmupRetryMaxMs);
    }

    /** 凭证预取子进程的总超时（含候选探测与三次 HTTP 往返）。 */
    public long prefetchTimeoutMs() {
        return prefetchTimeoutMs;
    }

    @Override
    public String toString() {
        return "Timings{punch=" + punchTimeoutMs + "ms probe=" + probeIntervalMs
                + "/" + probeTimeoutMs + "ms grace=" + startupGraceMs
                + "ms retry=" + warmupRetryInitialMs + ".." + warmupRetryMaxMs
                + "ms prefetch=" + prefetchTimeoutMs + "ms}";
    }
}
