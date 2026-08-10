package cn.ripplecraft.netherway.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 预下发协议：玩家进服<b>之前</b>在 Minecraft 端口上换取直连凭证。
 *
 * <p>不另开监听端口、不走 HTTP——服务器对外可达的地方从头到尾只有那一个
 * 被映射出去的 MC 端口。帧靠首字节嗅探与 MC 流量分叉：本协议以 {@code "NWAY"}
 * 开头，而 MC 现代握手第 2 字节是包 id {@code 0x00}、legacy ping 以
 * {@code 0xFE} 开头、PROXY protocol 以 {@code 'P'} 或 {@code 0x0D} 开头，
 * 最迟在第 2 字节就分得开（与 {@link ProxyProtocol} 共用同一条嗅探链）。
 *
 * <p><b>单步请求-响应，不做任何身份验证。</b>客户端自报用户名/UUID（用于
 * 签发绑定该玩家的每玩家令牌），服务端直接回凭证。准入交给 MC 服务端自己的
 * 白名单与正版验证——本协议只管把凭证送出去。帧不加密：换取「只暴露一个
 * 端口」的部署形态。
 *
 * <p>线格式（大端，与 {@link java.io.DataOutputStream} 一致）：
 *
 * <pre>
 * 请求  magic(4)="NWAY" | version(1) | op(1) | payloadLen(2) | payload
 * 响应                    version(1) | status(1) | payloadLen(2) | payload
 * </pre>
 *
 * <p>请求 payload 是 {@code UTF username, UTF uuid}；响应 OK 的 payload 是
 * 凭证裸字节（{@link Credentials#encode()}），响应 ERR 的 payload 是 {@code UTF reason}。
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
     * payload 长度上限。合法请求最大也就几百字节（用户名 + UUID），
     * 4 KB 已是数量级的余量；上限管住的是服务端在解析定论前需要缓冲的量。
     */
    public static final int MAX_PAYLOAD = 4096;

    /** 唯一操作码：请求凭证。 */
    public static final int OP_REQUEST = 1;

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

    /** 请求 payload：客户端自报用户名与 UUID（用于签发每玩家令牌，不做身份验证）。 */
    public static byte[] encodeIdentity(String username, String uuid) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.writeUTF(username);
        out.writeUTF(uuid);
        out.flush();
        return bo.toByteArray();
    }

    /** 解出请求 payload，返回 {@code [username, uuid]}。 */
    public static String[] decodeIdentity(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new String[] {in.readUTF(), in.readUTF()};
    }

    // ---------- 身份字段校验 ----------

    /**
     * 校验 username/uuid 的形状，合法返回空串，否则返回拒绝原因。
     *
     * <p>这不是身份验证——只防日志注入（换行伪造日志行）与畸形输入。
     * 用户名/UUID 的真实性由 MC 服务端自己的正版验证保证。
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

    /** 去掉 UUID 的连字符，比对前统一格式。 */
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
