package cn.ripplecraft.netherway.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 客户端回传给服务端的升级结果。
 *
 * <p>打洞结果只有客户端知道，不回传的话服主排查问题只能挨个找玩家要日志。
 * 走凭证同一条自定义频道，方向相反；服务端收到后记进自己的日志即可，
 * 不需要任何响应。
 *
 * <p>编解码与 {@link Credentials} 同一套纪律：裸字节、手写、不碰任何
 * mod 加载器的序列化机制。老客户端不发送、老服务端没有接收方，
 * 两个方向都自然兼容。
 *
 * <p>解码侧的输入来自网络上的任意客户端，必须按不可信数据对待：
 * 字段长度有上限，控制字符会被替换，防止往服务端日志里注入换行伪造记录。
 */
public final class UpgradeReport {

    /** 格式版本。将来追加字段时递增，解码按已知前缀读取。 */
    private static final byte FORMAT_VERSION = 1;

    private static final int MAX_ROOM_CHARS = 64;
    private static final int MAX_REASON_CHARS = 300;

    private final boolean upgraded;
    private final String room;
    private final String reason;
    private final int rttMs;
    private final int elapsedMs;

    private UpgradeReport(boolean upgraded, String room, String reason,
                          int rttMs, int elapsedMs) {
        this.upgraded = upgraded;
        this.room = sanitize(room, MAX_ROOM_CHARS);
        this.reason = sanitize(reason, MAX_REASON_CHARS);
        this.rttMs = Math.max(0, rttMs);
        this.elapsedMs = Math.max(0, elapsedMs);
    }

    /** 直连成功并已切换。rtt/elapsed 未测得时传 0。 */
    public static UpgradeReport upgraded(String room, long rttMs, long elapsedMs) {
        return new UpgradeReport(true, room, "", clampMs(rttMs), clampMs(elapsedMs));
    }

    /** 放弃直连。reason 会被截断与清洗后进服务端日志。 */
    public static UpgradeReport gaveUp(String room, String reason) {
        return new UpgradeReport(false, room, reason, 0, 0);
    }

    private static int clampMs(long ms) {
        if (ms < 0) {
            return 0;
        }
        return ms > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ms;
    }

    /**
     * 清洗不可信文本：控制字符换成空格（防日志注入），超长截断。
     * 发送侧与解码侧共用——发送侧的失败原因同样可能带着换行。
     */
    private static String sanitize(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(s.length(), maxChars));
        for (int i = 0; i < s.length() && sb.length() < maxChars; i++) {
            char c = s.charAt(i);
            sb.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (sb.length() < s.length()) {
            sb.append('…');
        }
        return sb.toString();
    }

    public byte[] encode() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        try {
            out.writeByte(FORMAT_VERSION);
            out.writeBoolean(upgraded);
            out.writeUTF(room);
            out.writeUTF(reason);
            out.writeInt(rttMs);
            out.writeInt(elapsedMs);
            out.flush();
        } catch (IOException e) {
            // ByteArrayOutputStream 不会真的抛 IO 异常
            throw new IllegalStateException("编码结果回执失败", e);
        }
        return buf.toByteArray();
    }

    /**
     * 解码。版本号更高时读已知前缀、忽略尾部追加的字段——客户端更新快于
     * 服务端很常见，老服务端仍能记录新客户端的回执。
     *
     * @throws IOException 数据损坏或版本过旧
     */
    public static UpgradeReport decode(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException(L10n.tr("report.emptyData"));
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int version = in.readByte() & 0xFF;
        if (version < FORMAT_VERSION) {
            throw new IOException(L10n.tr("report.versionTooOld", version));
        }
        boolean upgraded = in.readBoolean();
        String room = in.readUTF();
        String reason = in.readUTF();
        int rttMs = in.readInt();
        int elapsedMs = in.readInt();
        // 更高版本的尾部字段直接不读
        return new UpgradeReport(upgraded, room, reason, rttMs, elapsedMs);
    }

    public boolean isUpgraded() {
        return upgraded;
    }

    public String room() {
        return room;
    }

    /** 失败原因；成功时为空串。已清洗，可直接进日志。 */
    public String reason() {
        return reason;
    }

    /** 直连的往返延迟；0 表示未测得。 */
    public int rttMs() {
        return rttMs;
    }

    /** 从启动 agent 到隧道就绪的耗时；0 表示未测得。 */
    public int elapsedMs() {
        return elapsedMs;
    }

    @Override
    public String toString() {
        // 调试表示，刻意与语言无关：toString 不走 L10n
        if (upgraded) {
            return "UpgradeReport{upgraded room=" + room
                    + " rtt=" + rttMs + "ms elapsed=" + elapsedMs + "ms}";
        }
        return "UpgradeReport{gaveUp room=" + room + " reason=" + reason + "}";
    }
}
