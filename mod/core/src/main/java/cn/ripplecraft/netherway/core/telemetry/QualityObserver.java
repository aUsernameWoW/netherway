package cn.ripplecraft.netherway.core.telemetry;

/** Constant-time observation seam used by connection controllers. */
public interface QualityObserver {

    void record(QualitySummary summary);

    QualityObserver NOOP = new QualityObserver() {
        @Override
        public void record(QualitySummary summary) {
            // Deliberately empty.
        }
    };
}
