package cn.ripplecraft.netherway.core.telemetry;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Runs best-effort network flushes away from Minecraft and Netty threads. */
public final class TelemetryFlusher {

    private TelemetryFlusher() {}

    public static void start(final TelemetryCollector collector, long intervalSeconds) {
        if (collector == null || !collector.enabled() || !collector.transportConfigured()) return;
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable task) {
                        Thread thread = new Thread(task, "netherway-telemetry");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                if (collector.pendingCount() == 0) return;
                try {
                    collector.flush();
                } catch (IOException ignored) {
                    // The bounded queue retains the batch for the next interval.
                } catch (RuntimeException ignored) {
                    // Telemetry must never affect gameplay or stop future attempts.
                }
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }
}
