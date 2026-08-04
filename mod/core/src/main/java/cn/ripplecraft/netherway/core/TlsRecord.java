package cn.ripplecraft.netherway.core;

/**
 * TLS 记录层的首字节判定，用来在 Minecraft 端口上认出 frp 的控制连接。
 *
 * <p>会合点内嵌之后，玩家的 frp 控制连接和普通 MC 连接走同一个端口进来，
 * 嗅探器必须靠首字节把它们分开。frp 的控制通道默认开 TLS 且
 * {@code DisableCustomTLSFirstByte} 默认为真，所以连上来第一件事就是
 * 明文的 TLS ClientHello——记录类型 {@code 0x16}（handshake）+
 * 主版本 {@code 0x03}。实测线上首字节为 {@code 16 03 01 ...}。
 *
 * <p>与同端口上其它几种流量最迟第 2 字节就分得开：
 * <ul>
 *   <li>MC 现代握手：第 2 字节是包 id {@code 0x00}</li>
 *   <li>MC legacy ping：首字节 {@code 0xFE}</li>
 *   <li>PROXY protocol：首字节 {@code 'P'} 或 {@code 0x0D}</li>
 *   <li>预认证帧：以 {@code NWAY} 开头（见 {@link PreauthProtocol}）</li>
 * </ul>
 *
 * <p>判定刻意只看这两个字节、不解析长度也不看版本号细节：嗅探器只需要
 * 知道「这条连接归谁」，之后的字节原样转发给会合点，由 frp 自己解析。
 * 少解析一点就少一处能被畸形输入绊倒的地方。
 */
public final class TlsRecord {

    /** 记录类型：handshake。 */
    private static final int TYPE_HANDSHAKE = 0x16;
    /** 记录层主版本；TLS 1.0-1.3 的记录层都写 3。 */
    private static final int VERSION_MAJOR = 0x03;

    /** 下定论所需的字节数。 */
    public static final int PEEK_BYTES = 2;

    private TlsRecord() {
    }

    /**
     * 前若干字节是否像一个 TLS handshake 记录。
     *
     * <p>三态与 {@link PreauthProtocol#looksLikeFrame} 一致：{@code TRUE}
     * 确定是、{@code FALSE} 确定不是、{@code null} 字节还不够下定论。
     */
    public static Boolean looksLikeHandshake(byte[] buf, int len) {
        if (len <= 0) {
            return null;
        }
        if ((buf[0] & 0xFF) != TYPE_HANDSHAKE) {
            return Boolean.FALSE;
        }
        if (len < PEEK_BYTES) {
            return null;
        }
        return (buf[1] & 0xFF) == VERSION_MAJOR ? Boolean.TRUE : Boolean.FALSE;
    }
}
