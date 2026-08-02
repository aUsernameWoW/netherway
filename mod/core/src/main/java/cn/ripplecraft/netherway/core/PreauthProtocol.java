package cn.ripplecraft.netherway.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 预认证协议：玩家进服<b>之前</b>在 Minecraft 端口上换取直连凭证。
 *
 * <p>它刻意不另开监听端口，也不走 HTTP——服务器对外可达的地方从头到尾
 * 只有那一个被映射出去的 MC 端口。帧靠首字节嗅探与 MC 流量分叉：本协议
 * 以 {@code "NWAY"} 开头，而 MC 现代握手的第 2 字节是包 id {@code 0x00}、
 * legacy ping 以 {@code 0xFE} 开头、PROXY protocol 以 {@code 'P'} 或
 * {@code 0x0D} 开头，最迟在第 2 字节就分得开（与 {@link ProxyProtocol}
 * 共用同一条嗅探链，见平台层的注入器）。
 *
 * <p><b>帧不加密。</b>凭证（房间密钥、frps 令牌、每玩家令牌）以明文过网，
 * 这是刻意取舍：换取「只暴露一个端口」的部署形态。accessToken 不在此列——
 * 它只在玩家本机与皮肤站之间走 HTTPS，从不进入本协议的任何一帧。
 *
 * <p>线格式（大端，与 {@link java.io.DataOutputStream} 一致）：
 *
 * <pre>
 * 请求  magic(4)="NWAY" | version(1) | op(1) | payloadLen(2) | payload
 * 响应                    version(1) | status(1) | payloadLen(2) | payload
 * </pre>
 *
 * <p>请求 {@link #OP_HELLO} 的 payload 是 {@code UTF username, UTF uuid}，
 * 响应 payload 是 {@code byte mode, UTF serverId, UTF authServer}；
 * 请求 {@link #OP_CONFIRM} 的 payload 是 {@code UTF serverId, UTF username,
 * UTF uuid}，响应 payload 是 {@code UTF room, UTF backendId, int credLen,
 * byte[] cred}。错误响应的 payload 一律是 {@code UTF reason}。
 *
 * <p>与 Go 侧 {@code internal/preauth} 是<b>逐字节一致</b>的跨语言契约，
 * 改动必须两边同步——两侧各有一个用同一组常量的已知答案测试钉住这一点。
 */
public final class PreauthProtocol {

    /** 帧起始魔数。选它是因为首字节 'N'(0x4E) 与 MC/PROXY 的任何起始字节都不同。 */
    public static final byte[] MAGIC = {'N', 'W', 'A', 'Y'};

    /** 协议版本。不兼容变更时递增；服务端对未知版本回 {@link #STATUS_ERR}。 */
    public static final int VERSION = 1;

    /** 请求头长度：magic(4) + version(1) + op(1) + payloadLen(2)。 */
    public static final int REQUEST_HEADER_LEN = 8;

    /** 响应头长度：version(1) + status(1) + payloadLen(2)。 */
    public static final int RESPONSE_HEADER_LEN = 4;

    /**
     * payload 长度上限。合法请求最大也就几百字节（用户名 + UUID + serverId），
     * 4 KB 已是数量级的余量；上限管住的是服务端在解析定论前需要缓冲的量。
     */
    public static final int MAX_PAYLOAD = 4096;

    /** 领取 serverId：客户端自报身份，服务端给出 serverId 与皮肤站地址。 */
    public static final int OP_HELLO = 1;

    /** 验证并换取凭证：客户端已向皮肤站报到，请服务端查证。 */
    public static final int OP_CONFIRM = 2;

    /** 服务端不做皮肤站查证（online-mode=false），客户端应跳过 join 直接 confirm。 */
    public static final int MODE_OFFLINE = 0;

    /** 服务端会查证 hasJoined，客户端必须先拿 accessToken 去皮肤站 join。 */
    public static final int MODE_ONLINE = 1;

    public static final int STATUS_OK = 0;
    public static final int STATUS_ERR = 1;

    private PreauthProtocol() {
    }

    /**
     * 前若干字节是否可能是本协议的帧。
     *
     * <p>三态：{@code TRUE} 确定是（magic 已全部匹配）、{@code FALSE} 确定不是、
     * {@code null} 字节还不够下定论。嗅探器据此决定接管连接还是放行给 MC。
     */
    public static Boolean looksLikeFrame(byte[] buf, int len) {
        int n = Math.min(len, MAGIC.length);
        for (int i = 0; i < n; i++) {
            if (buf[i] != MAGIC[i]) {
                return Boolean.FALSE;
            }
        }
        return n == MAGIC.length ? Boolean.TRUE : null;
    }

    /** 一个解析出来的请求帧。 */
    public static final class Request {
        public final int version;
        public final int op;
        public final byte[] payload;

        Request(int version, int op, byte[] payload) {
            this.version = version;
            this.op = op;
            this.payload = payload;
        }

        /** 帧在输入流里占用的总字节数。 */
        public int frameLength() {
            return REQUEST_HEADER_LEN + payload.length;
        }
    }

    /**
     * 从 {@code buf} 的前 {@code len} 字节里解出一个完整请求帧。
     *
     * @return 字节还不够时返回 null（调用方继续攒），够了返回帧
     * @throws IOException 魔数不符或 payload 超限——这种连接直接断开
     */
    public static Request readRequest(byte[] buf, int len) throws IOException {
        if (len < REQUEST_HEADER_LEN) {
            if (Boolean.FALSE.equals(looksLikeFrame(buf, len))) {
                throw new IOException("不是 netherway 预认证帧");
            }
            return null;
        }
        if (!Boolean.TRUE.equals(looksLikeFrame(buf, len))) {
            throw new IOException("不是 netherway 预认证帧");
        }
        int version = buf[4] & 0xFF;
        int op = buf[5] & 0xFF;
        int payloadLen = ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
        if (payloadLen > MAX_PAYLOAD) {
            throw new IOException("payload 超过上限 " + MAX_PAYLOAD + " 字节");
        }
        if (len < REQUEST_HEADER_LEN + payloadLen) {
            return null;
        }
        byte[] payload = new byte[payloadLen];
        System.arraycopy(buf, REQUEST_HEADER_LEN, payload, 0, payloadLen);
        return new Request(version, op, payload);
    }

    /** 组装一个请求帧（客户端用；Java 侧目前只有测试会调）。 */
    public static byte[] encodeRequest(int op, byte[] payload) {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload 超过上限 " + MAX_PAYLOAD + " 字节");
        }
        byte[] out = new byte[REQUEST_HEADER_LEN + payload.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        out[4] = (byte) VERSION;
        out[5] = (byte) op;
        out[6] = (byte) ((payload.length >>> 8) & 0xFF);
        out[7] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, out, REQUEST_HEADER_LEN, payload.length);
        return out;
    }

    /** 组装一个响应帧。 */
    public static byte[] encodeResponse(int status, byte[] payload) {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload 超过上限 " + MAX_PAYLOAD + " 字节");
        }
        byte[] out = new byte[RESPONSE_HEADER_LEN + payload.length];
        out[0] = (byte) VERSION;
        out[1] = (byte) status;
        out[2] = (byte) ((payload.length >>> 8) & 0xFF);
        out[3] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, out, RESPONSE_HEADER_LEN, payload.length);
        return out;
    }

    /** 错误响应：payload 只有一个原因字符串。 */
    public static byte[] errorResponse(String reason) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeUTF(reason == null ? "" : reason);
            out.flush();
            return encodeResponse(STATUS_ERR, bo.toByteArray());
        } catch (IOException impossible) {
            // ByteArrayOutputStream 不会抛
            throw new IllegalStateException(impossible);
        }
    }

    // ---------- payload 编解码 ----------

    /** HELLO 请求的 payload。 */
    public static byte[] encodeHello(String username, String uuid) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.writeUTF(username);
        out.writeUTF(uuid);
        out.flush();
        return bo.toByteArray();
    }

    /** 解出 HELLO 请求的 payload，返回 {@code [username, uuid]}。 */
    public static String[] decodeHello(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new String[] {in.readUTF(), in.readUTF()};
    }

    /** HELLO 响应的 payload。 */
    public static byte[] encodeHelloReply(int mode, String serverId, String authServer)
            throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.writeByte(mode);
        out.writeUTF(serverId);
        out.writeUTF(authServer == null ? "" : authServer);
        out.flush();
        return bo.toByteArray();
    }

    /** CONFIRM 请求的 payload。 */
    public static byte[] encodeConfirm(String serverId, String username, String uuid)
            throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.writeUTF(serverId);
        out.writeUTF(username);
        out.writeUTF(uuid);
        out.flush();
        return bo.toByteArray();
    }

    /** 解出 CONFIRM 请求的 payload，返回 {@code [serverId, username, uuid]}。 */
    public static String[] decodeConfirm(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new String[] {in.readUTF(), in.readUTF(), in.readUTF()};
    }

    /** CONFIRM 响应的 payload：凭证本体以裸字节随行。 */
    public static byte[] encodeConfirmReply(String room, String backendId, byte[] credential)
            throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.writeUTF(room == null ? "" : room);
        out.writeUTF(backendId == null ? "" : backendId);
        out.writeInt(credential.length);
        out.write(credential);
        out.flush();
        return bo.toByteArray();
    }

    // ---------- 身份字段校验 ----------

    /**
     * 校验 username/uuid 的形状，合法返回空串，否则返回拒绝原因。
     *
     * <p>这两个字段会进日志，拒绝控制字符是从源头掐死日志注入（换行伪造
     * 日志行）；与 {@link UpgradeReport} 的 sanitize 是同一条纪律。用户名的
     * 具体合法性交给皮肤站的 hasJoined 判定——皮肤站不一定限于 Mojang 的
     * 字符集（可能允许中文）。
     */
    public static String validateIdentity(String username, String uuid) {
        if (username == null || username.isEmpty() || uuid == null || uuid.isEmpty()) {
            return "username 和 uuid 不能为空";
        }
        if (username.length() > 32 || hasControlChar(username)) {
            return "username 不合法";
        }
        if (!isHex32(normalizeUuid(uuid))) {
            return "uuid 不合法（应为 32 位 hex，可带连字符）";
        }
        return "";
    }

    /**
     * serverId 的形状校验。服务端自己签发的是 32 位 hex，放宽到 64 字符以内的
     * hex 与连字符，给未来的格式变化留余量。
     */
    public static boolean validServerId(String s) {
        if (s == null || s.isEmpty() || s.length() > 64) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex && c != '-') {
                return false;
            }
        }
        return true;
    }

    /** 去掉 UUID 的连字符。皮肤站返回的不带，请求侧可能带，比对前统一。 */
    public static String normalizeUuid(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "");
    }

    private static boolean hasControlChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHex32(String s) {
        if (s.length() != 32) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
