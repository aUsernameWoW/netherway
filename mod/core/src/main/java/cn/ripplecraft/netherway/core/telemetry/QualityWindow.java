package cn.ripplecraft.netherway.core.telemetry;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 无限后台流程的有界汇总窗口。
 *
 * <p>首次连续失败在第五次真实操作或窗口满三十分钟时结算；结算后若仍然
 * 失败，六小时内不再产生第二条。成功会立即结算失败窗口，但连续成功也只
 * 每六小时采一条，避免一个上游成功、下游持续失败时反复刷入相同事件。
 * 所有计数最终仍由 {@link QualitySummary} 压成固定桶。
 *
 * <p>本类只维护常数大小的本地状态并返回待上报摘要，不持有地址、异常或
 * 其他自由文本，也不负责 I/O。方法同步是为了兼容 agent 事件线程与守望
 * 线程同时到达；调用方应在锁外把返回值交给 observer。
 */
public final class QualityWindow {

    /** Monotonic clock seam used by deterministic tests and embedders with a virtual clock. */
    public interface Clock {
        long nanoTime();
    }

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    };

    private static final int INITIAL_ATTEMPTS = 5;
    private static final long INITIAL_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(30L);
    private static final long STEADY_EMIT_NANOS = TimeUnit.HOURS.toNanos(6L);

    private final QualitySummary.Path path;
    private final QualitySummary.Source source;
    private final Clock clock;

    private int attempts;
    private boolean windowOpen;
    private long windowOpenedAt;
    private boolean failureSeen;
    /** 固定枚举的二维计数，用于选择窗口内最常见的失败，而非最后一次失败。 */
    private final int[][] failureCounts = new int[QualitySummary.FailureStage.values().length]
            [QualitySummary.FailureCode.values().length];
    private QualitySummary.FailureStage dominantFailureStage = QualitySummary.FailureStage.NONE;
    private QualitySummary.FailureCode dominantFailureCode = QualitySummary.FailureCode.NONE;
    private int dominantFailureCount;
    private QualitySummary.Outcome lastEmittedOutcome;
    private long lastEmittedAt;

    public QualityWindow(QualitySummary.Path path, QualitySummary.Source source) {
        this(path, source, SYSTEM_CLOCK);
    }

    public QualityWindow(QualitySummary.Path path, QualitySummary.Source source, Clock clock) {
        if (path == null) {
            throw new IllegalArgumentException("path must be set");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must be set");
        }
        this.path = path;
        this.source = source == null ? QualitySummary.Source.UNKNOWN : source;
        this.clock = clock;
    }

    /** 记录真正发生的操作次数；等待、退避和让路不得调用此方法。 */
    public synchronized void markAttempts(int count) {
        if (count <= 0) {
            return;
        }
        openWindow(clock.nanoTime());
        attempts = saturatingAdd(attempts, count);
    }

    /**
     * 记录一个失败轮次；只有窗口应当结算时才返回摘要，否则返回 {@code null}。
     */
    public synchronized QualitySummary failed(QualitySummary.Stage stage,
                                               QualitySummary.FailureStage failureStage,
                                               QualitySummary.FailureCode failureCode) {
        long now = clock.nanoTime();
        openWindow(now);
        failureSeen = true;
        rememberFailure(failureStage, failureCode);

        boolean continuingFailure = lastEmittedOutcome == QualitySummary.Outcome.FAILED;
        boolean due = continuingFailure
                ? elapsedAtLeast(now, lastEmittedAt, STEADY_EMIT_NANOS)
                : attempts >= INITIAL_ATTEMPTS
                        || elapsedAtLeast(now, windowOpenedAt, INITIAL_WINDOW_NANOS);
        if (!due) {
            return null;
        }

        QualitySummary summary = QualitySummary.of(path, stage, QualitySummary.Outcome.FAILED)
                .withSource(source)
                .withAttempts(attempts)
                .withFailure(dominantFailureStage, dominantFailureCode);
        settle(QualitySummary.Outcome.FAILED, now);
        return summary;
    }

    /**
     * 结算成功。首次成功或失败后的恢复立即返回摘要；没有失败夹在中间的
     * 重复成功最多六小时返回一条。
     */
    public synchronized QualitySummary succeeded(QualitySummary.Stage stage,
                                                  long elapsedMs, long rttMs) {
        long now = clock.nanoTime();
        boolean repeatedSuccess = lastEmittedOutcome == QualitySummary.Outcome.SUCCESS
                && !failureSeen;
        if (repeatedSuccess && !elapsedAtLeast(now, lastEmittedAt, STEADY_EMIT_NANOS)) {
            // 这一轮已经成功，继续累计没有诊断价值；丢掉它，避免下一次
            // 真失败把此前的成功尝试误算进失败窗口。
            resetWindow();
            return null;
        }

        QualitySummary summary = QualitySummary.of(path, stage, QualitySummary.Outcome.SUCCESS)
                .withSource(source)
                .withAttempts(attempts)
                .withTimings(elapsedMs, rttMs);
        settle(QualitySummary.Outcome.SUCCESS, now);
        return summary;
    }

    private void openWindow(long now) {
        if (!windowOpen) {
            windowOpen = true;
            windowOpenedAt = now;
        }
    }

    private void settle(QualitySummary.Outcome outcome, long now) {
        resetWindow();
        lastEmittedOutcome = outcome;
        lastEmittedAt = now;
    }

    private void resetWindow() {
        attempts = 0;
        windowOpen = false;
        windowOpenedAt = 0L;
        failureSeen = false;
        for (int[] row : failureCounts) {
            Arrays.fill(row, 0);
        }
        dominantFailureStage = QualitySummary.FailureStage.NONE;
        dominantFailureCode = QualitySummary.FailureCode.NONE;
        dominantFailureCount = 0;
    }

    private void rememberFailure(QualitySummary.FailureStage stage,
                                 QualitySummary.FailureCode code) {
        QualitySummary.FailureStage safeStage = stage == null
                ? QualitySummary.FailureStage.NONE : stage;
        QualitySummary.FailureCode safeCode = code == null
                ? QualitySummary.FailureCode.NONE : code;
        int current = failureCounts[safeStage.ordinal()][safeCode.ordinal()];
        int next = current == Integer.MAX_VALUE ? current : current + 1;
        failureCounts[safeStage.ordinal()][safeCode.ordinal()] = next;
        // 严格大于才替换，票数相同则保留更早出现的分类，结果稳定。
        if (next > dominantFailureCount) {
            dominantFailureCount = next;
            dominantFailureStage = safeStage;
            dominantFailureCode = safeCode;
        }
    }

    private static boolean elapsedAtLeast(long now, long then, long interval) {
        return now - then >= interval;
    }

    private static int saturatingAdd(int current, int delta) {
        return delta >= Integer.MAX_VALUE - current ? Integer.MAX_VALUE : current + delta;
    }
}
