package cn.ripplecraft.netherway.core.telemetry;

/** Immutable local limits and user-selected telemetry level. */
public final class TelemetryConfig {

    public static final int DEFAULT_MAX_PENDING = 128;
    private static final int HARD_MAX_PENDING = 1024;

    private final boolean enabled;
    private final boolean enhanced;
    private final int maxPending;

    public TelemetryConfig(boolean enabled, boolean enhanced, int maxPending) {
        this.enabled = enabled;
        this.enhanced = enabled && enhanced;
        this.maxPending = Math.max(1, Math.min(HARD_MAX_PENDING, maxPending));
    }

    public static TelemetryConfig enabled(boolean enhanced) {
        return new TelemetryConfig(true, enhanced, DEFAULT_MAX_PENDING);
    }

    public static TelemetryConfig disabled() {
        return new TelemetryConfig(false, false, DEFAULT_MAX_PENDING);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean enhanced() {
        return enhanced;
    }

    public int maxPending() {
        return maxPending;
    }
}
