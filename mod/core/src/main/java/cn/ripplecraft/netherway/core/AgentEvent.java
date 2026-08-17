package cn.ripplecraft.netherway.core;

import java.util.Map;

/**
 * agent 在 stdout 上逐行输出的状态事件。
 *
 * <p>对应 Go 侧 {@code cmd/netherway/modbridge.go} 中的 event 结构。
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
        /**
         * 就绪后的隧道持续自检失败（典型原因：服务端重启、密钥轮换，
         * backend 只会拿旧凭证无限重试）。建议性事件，agent 进程不退出：
         * 预热侧据此立即刷新凭证并重建，正承载玩家连接时则忽略。
         */
        DEGRADED,
        /** 无法识别的事件名。新版 agent 加了事件时老版 mod 会走到这里。 */
        UNKNOWN
    }

    private final Type type;
    private final int port;
    private final long elapsedMs;
    private final long rttMs;
    private final String version;
    private final int online;
    private final String failureStage;
    private final String failureCode;
    private final String nat;
    private final String reason;

    private AgentEvent(Type type, int port, long elapsedMs, long rttMs,
                       String version, int online, String failureStage,
                       String failureCode, String nat, String reason) {
        this.type = type;
        this.port = port;
        this.elapsedMs = elapsedMs;
        this.rttMs = rttMs;
        this.version = version;
        this.online = online;
        this.failureStage = failureStage;
        this.failureCode = failureCode;
        this.nat = nat;
        this.reason = reason;
    }

    /**
     * 直接构造一个失败事件。
     *
     * <p>用于 agent 进程异常退出这类没有 JSON 输出的情形——绕开解析，
     * 免得原因文本里的引号反过来把构造弄坏。
     */
    public static AgentEvent failed(String reason) {
        return failed(null, null, reason);
    }

    /**
     * 直接构造一个带稳定分类的失败事件。
     *
     * <p>{@code reason} 仍只用于本地诊断；统计代码应只读取 stage/code。
     */
    public static AgentEvent failed(String failureStage, String failureCode, String reason) {
        return new AgentEvent(Type.FAILED, 0, 0L, 0L, null, 0,
                failureStage, failureCode, null, reason);
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
        } else if ("degraded".equals(event)) {
            type = Type.DEGRADED;
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
                m.get("failureStage"),
                m.get("failureCode"),
                m.get("nat"),
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

    /** 失败发生的稳定阶段；旧版 agent 未提供时为 null。 */
    public String failureStage() {
        return failureStage;
    }

    /** 稳定、低基数的失败码；旧版 agent 未提供时为 null。 */
    public String failureCode() {
        return failureCode;
    }

    /** agent 探测出的 NAT 形态（easy/hard）；未探得或旧版 agent 为 null。 */
    public String nat() {
        return nat;
    }

    /** 供本地日志展示的自由文本原因，仅 FAILED/STOPPED 可能有值。 */
    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return "AgentEvent{" + type + " port=" + port + " elapsedMs=" + elapsedMs
                + " rttMs=" + rttMs
                + (failureStage == null ? "" : " failureStage=" + failureStage)
                + (failureCode == null ? "" : " failureCode=" + failureCode)
                + (nat == null ? "" : " nat=" + nat)
                + (reason == null ? "" : " reason=" + reason) + "}";
    }
}
