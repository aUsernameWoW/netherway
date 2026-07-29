package cn.ripplecraft.xtcpinmc.core;

import java.util.Map;

/**
 * agent 在 stdout 上逐行输出的状态事件。
 *
 * <p>对应 Go 侧 {@code cmd/xtcpinmc/modbridge.go} 中的 event 结构。
 * 两边字段名必须保持一致。
 */
public final class AgentEvent {

    public enum Type {
        /** 进程已起，端口已选定，开始打洞。 */
        STARTING,
        /** 隧道已就绪并通过 Minecraft 握手验证，可以切换过去。 */
        READY,
        /** 打洞超时或出错，应当放弃升级、留在原连接上。 */
        FAILED,
        /** 隧道结束（正常收到停止信号，或异常退出）。 */
        STOPPED,
        /** 无法识别的事件名。新版 agent 加了事件时老版 mod 会走到这里。 */
        UNKNOWN
    }

    private final Type type;
    private final int port;
    private final long elapsedMs;
    private final long rttMs;
    private final String version;
    private final int online;
    private final String reason;

    private AgentEvent(Type type, int port, long elapsedMs, long rttMs,
                       String version, int online, String reason) {
        this.type = type;
        this.port = port;
        this.elapsedMs = elapsedMs;
        this.rttMs = rttMs;
        this.version = version;
        this.online = online;
        this.reason = reason;
    }

    /**
     * 直接构造一个失败事件。
     *
     * <p>用于 agent 进程异常退出这类没有 JSON 输出的情形——绕开解析，
     * 免得原因文本里的引号反过来把构造弄坏。
     */
    public static AgentEvent failed(String reason) {
        return new AgentEvent(Type.FAILED, 0, 0L, 0L, null, 0, reason);
    }

    /**
     * 解析一行 stdout。
     *
     * @return 解析结果；该行不是合法 JSON 时返回 null（调用方应忽略该行，
     *         而不是当成错误——避免 agent 或其依赖偶然写了别的东西就中断升级）
     */
    public static AgentEvent parse(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            return null;
        }
        Map<String, String> m;
        try {
            m = Json.parseObject(trimmed);
        } catch (RuntimeException e) {
            return null;
        }

        String event = m.get("event");
        if (event == null) {
            return null;
        }
        Type type;
        if ("starting".equals(event)) {
            type = Type.STARTING;
        } else if ("ready".equals(event)) {
            type = Type.READY;
        } else if ("failed".equals(event)) {
            type = Type.FAILED;
        } else if ("stopped".equals(event)) {
            type = Type.STOPPED;
        } else {
            type = Type.UNKNOWN;
        }

        return new AgentEvent(
                type,
                (int) longOf(m, "port"),
                longOf(m, "elapsedMs"),
                longOf(m, "rttMs"),
                m.get("version"),
                (int) longOf(m, "online"),
                m.get("reason"));
    }

    private static long longOf(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public Type type() {
        return type;
    }

    /** 隧道在本机监听的端口，仅 STARTING/READY 有意义。 */
    public int port() {
        return port;
    }

    /** 从 agent 启动到隧道就绪的总耗时，含 STUN 探测与打洞。 */
    public long elapsedMs() {
        return elapsedMs;
    }

    /** 隧道稳定后的往返延迟；0 表示未测得。 */
    public long rttMs() {
        return rttMs;
    }

    /** 服务端报告的游戏版本，可用于校验连的是不是同一个服。 */
    public String version() {
        return version;
    }

    public int online() {
        return online;
    }

    /** 失败原因，仅 FAILED/STOPPED 可能有值。 */
    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return "AgentEvent{" + type + " port=" + port + " elapsedMs=" + elapsedMs
                + " rttMs=" + rttMs + (reason == null ? "" : " reason=" + reason) + "}";
    }
}
