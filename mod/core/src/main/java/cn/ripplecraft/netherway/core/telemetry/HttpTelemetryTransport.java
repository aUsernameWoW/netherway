package cn.ripplecraft.netherway.core.telemetry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

/** Minimal HTTPS transport with bounded blocking time and no redirect or cookie state. */
public final class HttpTelemetryTransport implements TelemetryTransport {

    private final URL endpoint;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public HttpTelemetryTransport(String endpoint, int connectTimeoutMs, int readTimeoutMs) {
        try {
            this.endpoint = new URL(endpoint);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("invalid telemetry endpoint", e);
        }
        if (!"https".equalsIgnoreCase(this.endpoint.getProtocol())) {
            throw new IllegalArgumentException("telemetry endpoint must use HTTPS");
        }
        this.connectTimeoutMs = boundedTimeout(connectTimeoutMs);
        this.readTimeoutMs = boundedTimeout(readTimeoutMs);
    }

    @Override
    public boolean send(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) {
            return false;
        }
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Netherway-Telemetry/1");
        connection.setFixedLengthStreamingMode(payload.length);
        try {
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                out.close();
            }
            int status = connection.getResponseCode();
            closeQuietly(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            return status == HttpURLConnection.HTTP_NO_CONTENT;
        } finally {
            connection.disconnect();
        }
    }

    private static int boundedTimeout(int value) {
        return Math.max(250, Math.min(10_000, value));
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
            // Response bodies are irrelevant; disconnect below owns final cleanup.
        }
    }
}
