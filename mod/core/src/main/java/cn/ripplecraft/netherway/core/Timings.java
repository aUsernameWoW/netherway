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

    private final long punchTimeoutMs;
    private final long probeIntervalMs;
    private final long probeTimeoutMs;
    private final long startupGraceMs;

    public Timings(long punchTimeoutMs, long probeIntervalMs,
                   long probeTimeoutMs, long startupGraceMs) {
        this.punchTimeoutMs = punchTimeoutMs;
        this.probeIntervalMs = probeIntervalMs;
        this.probeTimeoutMs = probeTimeoutMs;
        this.startupGraceMs = startupGraceMs;
    }

    public static Timings defaults() {
        return new Timings(DEFAULT_PUNCH_TIMEOUT_MS, DEFAULT_PROBE_INTERVAL_MS,
                DEFAULT_PROBE_TIMEOUT_MS, DEFAULT_STARTUP_GRACE_MS);
    }

    /** 把非正值回填成默认值，避免配置文件写了 0 导致空转或死等。 */
    public Timings normalized() {
        return new Timings(
                punchTimeoutMs > 0 ? punchTimeoutMs : DEFAULT_PUNCH_TIMEOUT_MS,
                probeIntervalMs > 0 ? probeIntervalMs : DEFAULT_PROBE_INTERVAL_MS,
                probeTimeoutMs > 0 ? probeTimeoutMs : DEFAULT_PROBE_TIMEOUT_MS,
                startupGraceMs > 0 ? startupGraceMs : DEFAULT_STARTUP_GRACE_MS);
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

    @Override
    public String toString() {
        return "Timings{punch=" + punchTimeoutMs + "ms probe=" + probeIntervalMs
                + "/" + probeTimeoutMs + "ms grace=" + startupGraceMs + "ms}";
    }
}
