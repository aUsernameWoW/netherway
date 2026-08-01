package cn.ripplecraft.netherway.core;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * PROXY protocol（HAProxy 定义的连接头）的嗅探式解析器，v1 与 v2 都认。
 *
 * <p>serve 侧的 frp 配了 {@code -proxy-protocol} 后，会在连本地 MC 端口前
 * 先发一个头，把来访连接的真实源地址带给 MC 服务端。手写的原因与
 * {@link Json} 相同：core 零依赖；而且 1.7.10 自带的 Netty 4.0.x 根本
 * 没有 haproxy codec（4.1 才加入），想借也没得借。
 *
 * <p><b>必须嗅探、绝不能要求头一定存在</b>：当前 frp（v0.70.0）只有 stcp
 * 中转路径真的带头，xtcp 的 P2P 流要等上游支持（fatedier/frp#2748）；
 * 预热直连、老版本 agent 的流量也永远无头。好在无歧义——MC 现代握手的
 * 第二个字节是包 id 0x00，legacy ping 以 0xFE 开头，与 v1 前缀
 * {@code "PROXY "} 及 v2 的 12 字节签名最迟在第 2 字节就分叉。
 *
 * <p>只做字节判定，不做信任判定：<b>头是谁都能伪造的，调用方必须自己
 * 把关来源</b>（本项目只对来自回环地址的连接嗅探，因为 frp 从本机拨入）。
 */
public final class ProxyProtocol {

    /** v1 整行（含 CRLF）的规格上限。 */
    public static final int V1_MAX = 107;

    /**
     * v2 头 16 字节之后附加数据（地址块 + TLV）的接受上限。规格允许到
     * 65535，但 frp（go-proxyproto）只发裸地址块（IPv4 是 12 字节）；
     * 上限管住的是解析定论前调用方需要缓冲的量。
     */
    public static final int V2_MAX_PAYLOAD = 4096;

    private static final byte[] V2_SIG = {
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A,
    };
    private static final byte[] V1_PREFIX = {'P', 'R', 'O', 'X', 'Y', ' '};

    private ProxyProtocol() {
    }

    /** 解析定论。 */
    public enum Status {
        /** 字节还不够下定论，继续攒。解析器保证攒到有限字节内必出定论。 */
        NEED_MORE,
        /** 不是 PROXY 头，一个字节都没消费，原样交给 MC 解码链。 */
        NOT_PRESENT,
        /** 是 PROXY 头，跳过 {@code headerLength} 字节后交回剩余数据。 */
        PRESENT,
        /** 前缀像 PROXY 头但内容非法。真 MC 客户端不会发这种字节，断开即可。 */
        INVALID,
    }

    /** 一次解析的结果。 */
    public static final class Result {
        public final Status status;
        /** {@link Status#PRESENT} 时头部占用的字节数。 */
        public final int headerLength;
        /**
         * {@link Status#PRESENT} 且头部携带可用地址时非 null；
         * v1 UNKNOWN、v2 LOCAL/UNSPEC/UNIX 只剥头，没有来源地址。
         */
        public final InetSocketAddress source;
        /** {@link Status#INVALID} 的原因。只描述结构，不回显数据内容，可进日志。 */
        public final String error;

        private Result(Status status, int headerLength, InetSocketAddress source, String error) {
            this.status = status;
            this.headerLength = headerLength;
            this.source = source;
            this.error = error;
        }
    }

    private static final Result NEED_MORE = new Result(Status.NEED_MORE, 0, null, null);
    private static final Result NOT_PRESENT = new Result(Status.NOT_PRESENT, 0, null, null);

    private static Result present(int headerLength, InetSocketAddress source) {
        return new Result(Status.PRESENT, headerLength, source, null);
    }

    private static Result invalid(String error) {
        return new Result(Status.INVALID, 0, null, error);
    }

    /** 对 {@code buf} 的前 {@code len} 字节（连接最初收到的字节）做定论。 */
    public static Result parse(byte[] buf, int len) {
        if (len <= 0) {
            return NEED_MORE;
        }
        // v1 首字节 'P'(0x50)、v2 首字节 0x0D，第 0 字节即分叉
        if (buf[0] == V2_SIG[0]) {
            return parseV2(buf, len);
        }
        if (buf[0] == V1_PREFIX[0]) {
            return parseV1(buf, len);
        }
        return NOT_PRESENT;
    }

    /** 同上，取整个数组。 */
    public static Result parse(byte[] buf) {
        return parse(buf, buf.length);
    }

    // ---------- v2（二进制格式）----------

    private static Result parseV2(byte[] buf, int len) {
        int n = Math.min(len, V2_SIG.length);
        for (int i = 0; i < n; i++) {
            if (buf[i] != V2_SIG[i]) {
                return NOT_PRESENT;
            }
        }
        if (len < 16) {
            return NEED_MORE;
        }
        int verCmd = buf[12] & 0xFF;
        if ((verCmd >>> 4) != 0x2) {
            return invalid("v2 签名后的版本号不是 2");
        }
        int cmd = verCmd & 0x0F;
        if (cmd > 1) {
            return invalid("v2 命令字段非法");
        }
        int family = (buf[13] & 0xFF) >>> 4;
        if (family > 3) {
            return invalid("v2 地址族非法");
        }
        int payload = ((buf[14] & 0xFF) << 8) | (buf[15] & 0xFF);
        if (payload > V2_MAX_PAYLOAD) {
            return invalid("v2 附加数据超过接受上限 " + V2_MAX_PAYLOAD + " 字节");
        }
        int total = 16 + payload;
        if (len < total) {
            return NEED_MORE;
        }
        if (cmd == 0x0) {
            // LOCAL：发送方自己的连接（健康检查之类），地址就是连接本身的
            return present(total, null);
        }
        if (family == 0x1) { // AF_INET: src(4) dst(4) sport(2) dport(2)
            if (payload < 12) {
                return invalid("v2 IPv4 地址块不完整");
            }
            return present(total, address(buf, 16, 4, port(buf, 16 + 8)));
        }
        if (family == 0x2) { // AF_INET6: src(16) dst(16) sport(2) dport(2)
            if (payload < 36) {
                return invalid("v2 IPv6 地址块不完整");
            }
            return present(total, address(buf, 16, 16, port(buf, 16 + 32)));
        }
        // AF_UNSPEC / AF_UNIX：没有能用的来源地址，剥头即可
        return present(total, null);
    }

    private static int port(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
    }

    private static InetSocketAddress address(byte[] buf, int off, int size, int port) {
        byte[] raw = new byte[size];
        System.arraycopy(buf, off, raw, 0, size);
        try {
            return new InetSocketAddress(InetAddress.getByAddress(raw), port);
        } catch (UnknownHostException impossible) {
            // getByAddress 只在长度不是 4/16 时抛，这里长度固定
            return null;
        }
    }

    // ---------- v1（文本格式）----------

    private static Result parseV1(byte[] buf, int len) {
        int n = Math.min(len, V1_PREFIX.length);
        for (int i = 0; i < n; i++) {
            if (buf[i] != V1_PREFIX[i]) {
                return NOT_PRESENT;
            }
        }
        // 找 CRLF；规格保证整行（含 CRLF）不超过 107 字节
        int cr = -1;
        int scanEnd = Math.min(len, V1_MAX);
        for (int i = V1_PREFIX.length; i < scanEnd; i++) {
            if (buf[i] == '\r') {
                cr = i;
                break;
            }
        }
        if (cr < 0) {
            return len >= V1_MAX ? invalid("v1 头超过 " + V1_MAX + " 字节仍未见 CRLF") : NEED_MORE;
        }
        if (cr + 1 >= len) {
            return cr + 1 >= V1_MAX ? invalid("v1 头超长") : NEED_MORE;
        }
        if (buf[cr + 1] != '\n') {
            return invalid("v1 头的 CR 后不是 LF");
        }

        String line = new String(buf, 0, cr, StandardCharsets.US_ASCII);
        String[] t = line.split(" ", -1);
        // t[0] 必为 "PROXY"（前缀已比对）；UNKNOWN 后面允许跟任意内容，一律忽略
        if (t.length >= 2 && "UNKNOWN".equals(t[1])) {
            return present(cr + 2, null);
        }
        if (t.length != 6) {
            return invalid("v1 头字段数不是 6");
        }
        boolean v4 = "TCP4".equals(t[1]);
        boolean v6 = "TCP6".equals(t[1]);
        if (!v4 && !v6) {
            return invalid("v1 协议族不是 TCP4/TCP6/UNKNOWN");
        }
        int srcPort = parsePort(t[4]);
        int dstPort = parsePort(t[5]);
        if (srcPort < 0 || dstPort < 0) {
            return invalid("v1 端口非法");
        }
        InetAddress src = v4 ? parseIPv4(t[2]) : parseIPv6(t[2]);
        InetAddress dst = v4 ? parseIPv4(t[3]) : parseIPv6(t[3]);
        if (src == null || dst == null) {
            return invalid("v1 地址不是合法的 IP 字面量");
        }
        return present(cr + 2, new InetSocketAddress(src, srcPort));
    }

    private static int parsePort(String s) {
        if (s.isEmpty() || s.length() > 5) {
            return -1;
        }
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return v <= 65535 ? v : -1;
    }

    /** 严格的点分十进制解析，不经 DNS。 */
    private static InetAddress parseIPv4(String s) {
        byte[] raw = new byte[4];
        int part = 0;
        int val = -1;
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (digits == 0 || part >= 3) {
                    return null;
                }
                raw[part++] = (byte) val;
                val = -1;
                digits = 0;
            } else if (c >= '0' && c <= '9') {
                val = (val < 0 ? 0 : val) * 10 + (c - '0');
                if (++digits > 3 || val > 255) {
                    return null;
                }
            } else {
                return null;
            }
        }
        if (part != 3 || digits == 0) {
            return null;
        }
        raw[3] = (byte) val;
        try {
            return InetAddress.getByAddress(raw);
        } catch (UnknownHostException impossible) {
            return null;
        }
    }

    /**
     * IPv6 字面量解析。冒号语法（含 :: 压缩、结尾内嵌 IPv4）自己展开太啰嗦，
     * 交给 {@link InetAddress#getByName}——先验过字符集且必含冒号，
     * 这样的串不可能被当成主机名去查 DNS（冒号在主机名里非法）。
     */
    private static InetAddress parseIPv6(String s) {
        boolean colon = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (c == ':') {
                colon = true;
            } else if (!hex && c != '.') {
                return null;
            }
        }
        if (!colon) {
            return null;
        }
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
