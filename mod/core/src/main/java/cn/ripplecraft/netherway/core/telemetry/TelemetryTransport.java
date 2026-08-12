package cn.ripplecraft.netherway.core.telemetry;

import java.io.IOException;

/** A replaceable batch sink. Controllers never depend on HTTP or storage. */
public interface TelemetryTransport {

    /** @return true only after the transport has durably accepted the batch. */
    boolean send(byte[] payload) throws IOException;

    TelemetryTransport NONE = new TelemetryTransport() {
        @Override
        public boolean send(byte[] payload) {
            return false;
        }
    };
}
