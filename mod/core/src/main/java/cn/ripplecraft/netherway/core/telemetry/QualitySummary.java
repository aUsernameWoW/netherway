package cn.ripplecraft.netherway.core.telemetry;

/**
 * One locally aggregated quality fact. Every dimension is a fixed enum or a bucket; there is no
 * free-text/map escape hatch by design.
 */
public final class QualitySummary {

    public enum Path {
        UPGRADE("upgrade"), WARMUP("warmup"), PREFETCH("prefetch"),
        /** Dedicated-server side serve process lifecycle. */
        SERVE("serve"),
        /** Internal placeholder used after an enhanced summary is reduced to basic. */
        BASIC("quality");
        private final String wire;
        Path(String wire) { this.wire = wire; }
        String wire() { return wire; }
    }

    public enum Stage {
        STARTED("started"), PUNCH_STARTED("punch_started"), TUNNEL_READY("tunnel_ready"),
        REDIRECT_STARTED("redirect_started"), REDIRECT_LANDED("redirect_landed"),
        ROUND_FINISHED("round_finished"), TUNNEL_LOST("tunnel_lost"),
        /** Internal placeholder used after an enhanced summary is reduced to basic. */
        SUMMARY("summary");
        private final String wire;
        Stage(String wire) { this.wire = wire; }
        String wire() { return wire; }
    }

    public enum Outcome {
        STARTED("started"), SUCCESS("success"), FAILED("failed"),
        INTERRUPTED("interrupted"), UNKNOWN("unknown");
        private final String wire;
        Outcome(String wire) { this.wire = wire; }
        String wire() { return wire; }
    }

    public enum Source {
        UNKNOWN("unknown"), COLD_AGENT("cold_agent"), WARMUP_REUSE("warmup_reuse"),
        DIRECT_ENTRY("direct_entry"), CONFIG("config"), SERVER_LIST("server_list");
        private final String wire;
        Source(String wire) { this.wire = wire; }
        String wire() { return wire; }
    }

    public enum FailureStage {
        NONE("none"), PLATFORM("platform"), EXTRACT("extract"), START("start"),
        BACKEND("backend"), PROBE("probe"), REDIRECT("redirect"),
        PREFETCH("prefetch"), INTERNAL("internal"), OTHER("other");
        private final String wire;
        FailureStage(String wire) { this.wire = wire; }
        String wire() { return wire; }

        public static FailureStage fromWire(String value) {
            if (value == null || value.isEmpty()) return NONE;
            for (FailureStage item : values()) if (item.wire.equals(value)) return item;
            return OTHER;
        }
    }

    public enum FailureCode {
        NONE("none"), BACKEND_UNKNOWN("backend_unknown"),
        BIND_PORT_FAILED("bind_port_failed"), BACKEND_EXITED("backend_exited"),
        READY_PROBE_TIMEOUT("ready_probe_timeout"), AGENT_EARLY_EXIT("agent_early_exit"),
        PLATFORM_UNSUPPORTED("platform_unsupported"),
        BINARY_EXTRACT_FAILED("binary_extract_failed"),
        AGENT_START_FAILED("agent_start_failed"), REDIRECT_FAILED("redirect_failed"),
        PREFETCH_FAILED("prefetch_failed"), INTERRUPTED("interrupted"),
        INTERNAL_ERROR("internal_error"), OTHER("other");
        private final String wire;
        FailureCode(String wire) { this.wire = wire; }
        String wire() { return wire; }

        public static FailureCode fromWire(String value) {
            if (value == null || value.isEmpty()) return NONE;
            for (FailureCode item : values()) if (item.wire.equals(value)) return item;
            return OTHER;
        }
    }

    /**
     * 本条摘要对应的隧道方案。刻意归一化成固定枚举而不透传服务端可控的
     * backendId 原文——schema 无自由文本逃生舱是设计红线，未识别的一律
     * 归入 {@link #OTHER}。新增 backend 时两侧同步：这里加枚举值，
     * ingest 的 allowed 列表加 wire 值。
     */
    public enum Backend {
        UNKNOWN("unknown"), FRP_XTCP("frp_xtcp"), OTHER("other");
        private final String wire;
        Backend(String wire) { this.wire = wire; }
        String wire() { return wire; }

        /** 从凭证的 backendId 归一化；null/空 → UNKNOWN，未识别 → OTHER。 */
        public static Backend fromBackendId(String backendId) {
            if (backendId == null || backendId.isEmpty()) return UNKNOWN;
            if ("frp-xtcp".equals(backendId)) return FRP_XTCP;
            return OTHER;
        }
    }

    /** agent 侧 STUN 分类出的 NAT 形态（对应 frp 的 EasyNAT/HardNAT）。 */
    public enum Nat {
        UNKNOWN("unknown"), EASY("easy"), HARD("hard");
        private final String wire;
        Nat(String wire) { this.wire = wire; }
        String wire() { return wire; }

        public static Nat fromWire(String value) {
            if ("easy".equals(value)) return EASY;
            if ("hard".equals(value)) return HARD;
            return UNKNOWN;
        }
    }

    private final Path path;
    private final Stage stage;
    private final Outcome outcome;
    private final Source source;
    private final FailureStage failureStage;
    private final FailureCode failureCode;
    private final Backend backend;
    private final Nat nat;
    private final int attempts;
    private final long elapsedMs;
    private final long rttMs;

    private QualitySummary(Path path, Stage stage, Outcome outcome, Source source,
                           FailureStage failureStage, FailureCode failureCode,
                           Backend backend, Nat nat,
                           int attempts, long elapsedMs, long rttMs) {
        if (path == null || stage == null || outcome == null) {
            throw new IllegalArgumentException("path/stage/outcome must be set");
        }
        this.path = path;
        this.stage = stage;
        this.outcome = outcome;
        this.source = source == null ? Source.UNKNOWN : source;
        this.failureStage = failureStage == null ? FailureStage.NONE : failureStage;
        this.failureCode = failureCode == null ? FailureCode.NONE : failureCode;
        this.backend = backend == null ? Backend.UNKNOWN : backend;
        this.nat = nat == null ? Nat.UNKNOWN : nat;
        this.attempts = Math.max(0, attempts);
        this.elapsedMs = Math.max(0L, elapsedMs);
        this.rttMs = Math.max(0L, rttMs);
    }

    public static QualitySummary of(Path path, Stage stage, Outcome outcome) {
        return new QualitySummary(path, stage, outcome, Source.UNKNOWN,
                FailureStage.NONE, FailureCode.NONE, Backend.UNKNOWN, Nat.UNKNOWN, 0, 0L, 0L);
    }

    public QualitySummary withSource(Source value) {
        return new QualitySummary(path, stage, outcome, value, failureStage, failureCode,
                backend, nat, attempts, elapsedMs, rttMs);
    }

    public QualitySummary withFailure(FailureStage failureStage, FailureCode failureCode) {
        return new QualitySummary(path, stage, outcome, source, failureStage, failureCode,
                backend, nat, attempts, elapsedMs, rttMs);
    }

    public QualitySummary withFailure(String failureStage, String failureCode) {
        return withFailure(FailureStage.fromWire(failureStage), FailureCode.fromWire(failureCode));
    }

    public QualitySummary withBackend(Backend value) {
        return new QualitySummary(path, stage, outcome, source, failureStage, failureCode,
                value, nat, attempts, elapsedMs, rttMs);
    }

    public QualitySummary withNat(Nat value) {
        return new QualitySummary(path, stage, outcome, source, failureStage, failureCode,
                backend, value, attempts, elapsedMs, rttMs);
    }

    public QualitySummary withAttempts(int value) {
        return new QualitySummary(path, stage, outcome, source, failureStage, failureCode,
                backend, nat, value, elapsedMs, rttMs);
    }

    public QualitySummary withTimings(long elapsedMs, long rttMs) {
        return new QualitySummary(path, stage, outcome, source, failureStage, failureCode,
                backend, nat, attempts, elapsedMs, rttMs);
    }

    /**
     * Basic mode only retains a terminal coarse result. Path, stage, failure classification,
     * backend, NAT class, attempts and timing values are deliberately erased before the item
     * enters the queue.
     */
    QualitySummary forBasicMode() {
        return new QualitySummary(Path.BASIC, Stage.SUMMARY, outcome, Source.UNKNOWN,
                FailureStage.NONE, FailureCode.NONE, Backend.UNKNOWN, Nat.UNKNOWN, 0, 0L, 0L);
    }

    /** Intermediate funnel events are enhanced-only and are not collected in basic mode. */
    boolean isTerminalForBasicMode() {
        if (outcome == Outcome.FAILED || outcome == Outcome.INTERRUPTED) {
            return true;
        }
        if (outcome != Outcome.SUCCESS) {
            return false;
        }
        return stage == Stage.REDIRECT_LANDED || stage == Stage.ROUND_FINISHED
                || (path == Path.WARMUP && stage == Stage.TUNNEL_READY)
                || (path == Path.SERVE && stage == Stage.TUNNEL_READY);
    }

    String path() { return path.wire(); }
    String stage() { return stage.wire(); }
    String outcome() { return outcome.wire(); }
    String source() { return source.wire(); }
    String failureStage() { return failureStage.wire(); }
    String failureCode() { return failureCode.wire(); }
    String backend() { return backend.wire(); }
    String nat() { return nat.wire(); }

    String attemptsBucket() {
        if (attempts <= 0) return "unknown";
        if (attempts == 1) return "1";
        if (attempts == 2) return "2";
        if (attempts <= 5) return "3_5";
        return "6_plus";
    }

    String elapsedBucket() {
        if (elapsedMs <= 0) return "unknown";
        if (elapsedMs < 1000) return "lt_1s";
        if (elapsedMs < 3000) return "1_3s";
        if (elapsedMs < 5000) return "3_5s";
        if (elapsedMs < 8000) return "5_8s";
        if (elapsedMs < 15000) return "8_15s";
        if (elapsedMs < 30000) return "15_30s";
        return "30s_plus";
    }

    String rttBucket() {
        if (rttMs <= 0) return "unknown";
        if (rttMs < 25) return "lt_25ms";
        if (rttMs < 50) return "25_50ms";
        if (rttMs < 100) return "50_100ms";
        if (rttMs < 200) return "100_200ms";
        return "200ms_plus";
    }
}
