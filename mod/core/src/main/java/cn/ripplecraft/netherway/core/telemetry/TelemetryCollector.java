package cn.ripplecraft.netherway.core.telemetry;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Thread-safe, bounded local quality collector. Recording is O(1) and never performs IO. */
public final class TelemetryCollector implements QualityObserver {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int SCHEMA_VERSION = 1;

    private final TelemetryConfig config;
    private final TelemetryEnvironment environment;
    private final TelemetryTransport transport;
    private final ArrayDeque<QualitySummary> pending = new ArrayDeque<QualitySummary>();
    private long dropped;
    /** Only one snapshot may be in flight, otherwise two successful flushes can over-drain. */
    private boolean flushing;
    /** Frozen across retries so a lost response cannot change the meaning of a batch ID. */
    private byte[] inFlightPayload;
    private int inFlightCount;
    private long inFlightDropped;

    public TelemetryCollector(TelemetryConfig config, TelemetryEnvironment environment) {
        this(config, environment, TelemetryTransport.NONE);
    }

    public TelemetryCollector(TelemetryConfig config, TelemetryEnvironment environment,
                              TelemetryTransport transport) {
        if (config == null || environment == null) {
            throw new IllegalArgumentException("config/environment must be set");
        }
        this.config = config;
        this.environment = environment;
        this.transport = transport == null ? TelemetryTransport.NONE : transport;
    }

    @Override
    public synchronized void record(QualitySummary summary) {
        if (!config.enabled() || summary == null) return;
        if (!config.enhanced()) {
            if (!summary.isTerminalForBasicMode()) return;
            summary = summary.forBasicMode();
        }
        if (pending.size() >= config.maxPending()) {
            dropped++;
            return;
        }
        pending.addLast(summary);
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized long droppedCount() {
        return dropped;
    }

    public boolean enabled() {
        return config.enabled();
    }

    public boolean enhanced() {
        return config.enhanced();
    }

    /** Whether this build has an actual sink rather than the inert development transport. */
    public boolean transportConfigured() {
        return transport != TelemetryTransport.NONE;
    }

    /** Returns a representative next batch without consuming it. */
    public synchronized String previewPayload() {
        if (inFlightPayload != null) return new String(inFlightPayload, UTF8);
        return encode(new ArrayList<QualitySummary>(pending), dropped, newBatchId());
    }

    /** Best-effort flush. Failed or unconfigured transports leave the queue untouched. */
    public boolean flush() throws IOException {
        final byte[] payload;
        synchronized (this) {
            if (!config.enabled() || pending.isEmpty() || flushing) return false;
            flushing = true;
            if (inFlightPayload == null) {
                List<QualitySummary> snapshot = new ArrayList<QualitySummary>(pending);
                inFlightCount = snapshot.size();
                inFlightDropped = dropped;
                inFlightPayload = encode(snapshot, inFlightDropped, newBatchId()).getBytes(UTF8);
            }
            payload = inFlightPayload;
        }
        try {
            if (!transport.send(payload)) return false;
            synchronized (this) {
                for (int i = 0; i < inFlightCount && !pending.isEmpty(); i++) {
                    pending.removeFirst();
                }
                dropped = Math.max(0L, dropped - inFlightDropped);
                inFlightPayload = null;
                inFlightCount = 0;
                inFlightDropped = 0L;
            }
            return true;
        } finally {
            synchronized (this) {
                flushing = false;
            }
        }
    }

    private String encode(List<QualitySummary> summaries, long droppedValue, String batchId) {
        StringBuilder out = new StringBuilder(256 + summaries.size() * 128);
        out.append('{');
        field(out, "schemaVersion", Integer.toString(SCHEMA_VERSION), false, false);
        field(out, "batchId", batchId, true, true);
        field(out, "enhanced", Boolean.toString(config.enhanced()), true, false);
        out.append(",\"environment\":{");
        field(out, "modVersion", environment.modVersion(), false, true);
        field(out, "mcVersion", environment.mcVersion(), true, true);
        field(out, "javaMajor", environment.javaMajor(), true, true);
        field(out, "osFamily", environment.osFamily(), true, true);
        field(out, "arch", environment.arch(), true, true);
        field(out, "role", environment.role().wire(), true, true);
        out.append('}');
        field(out, "dropped", Long.toString(droppedValue), true, false);
        out.append(",\"summaries\":[");
        boolean comma = false;
        for (QualitySummary summary : summaries) {
            if (comma) out.append(',');
            comma = true;
            appendSummary(out, summary);
        }
        return out.append("]}").toString();
    }

    private static String newBatchId() {
        return UUID.randomUUID().toString();
    }

    private void appendSummary(StringBuilder out, QualitySummary summary) {
        out.append('{');
        if (config.enhanced()) {
            field(out, "path", summary.path(), false, true);
            field(out, "stage", summary.stage(), true, true);
            field(out, "outcome", summary.outcome(), true, true);
            field(out, "failureStage", summary.failureStage(), true, true);
            field(out, "source", summary.source(), true, true);
            field(out, "failureCode", summary.failureCode(), true, true);
            field(out, "attempts", summary.attemptsBucket(), true, true);
            field(out, "elapsed", summary.elapsedBucket(), true, true);
            field(out, "rtt", summary.rttBucket(), true, true);
        } else {
            field(out, "outcome", summary.outcome(), false, true);
        }
        out.append('}');
    }

    private static void field(StringBuilder out, String name, String value,
                              boolean comma, boolean quoteValue) {
        if (comma) out.append(',');
        quote(out, name);
        out.append(':');
        if (quoteValue) quote(out, value); else out.append(value);
    }

    private static void quote(StringBuilder out, String value) {
        out.append('\"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        String hex = Integer.toHexString(c);
                        out.append("\\u");
                        for (int j = hex.length(); j < 4; j++) out.append('0');
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('\"');
    }
}
