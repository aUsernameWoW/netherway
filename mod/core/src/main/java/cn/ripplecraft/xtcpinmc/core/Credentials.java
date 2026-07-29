package cn.ripplecraft.xtcpinmc.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 服务端在玩家登录后下发的 P2P 凭证。
 *
 * <p>这是整套设计的安全基础：凭证不随客户端分发，而是玩家通过正版验证、
 * 白名单等既有校验连上服务器之后才拿得到。能拿到密钥的必然是有权进服的人，
 * 于是不需要另建一套鉴权。
 *
 * <p>编解码刻意用最朴素的 {@link DataOutputStream} 手写字节，
 * <b>不使用任何 mod 加载器的序列化机制</b>。Forge 1.13 之后网络 API 整个重写过，
 * 绑上去意味着每换一个版本就要重写一遍编解码；而裸字节在所有加载器、
 * 所有版本上都一样，平台适配层只需负责把 byte[] 送出去。
 */
public final class Credentials {

    /** 格式版本。新增字段时递增，旧客户端靠它决定读到哪为止。 */
    private static final byte FORMAT_VERSION = 1;

    private final String serverAddr;
    private final int serverPort;
    private final String token;
    private final String stunServer;
    private final String roomName;
    private final String secretKey;
    private final int punchTimeoutMs;

    public Credentials(String serverAddr, int serverPort, String token, String stunServer,
                       String roomName, String secretKey, int punchTimeoutMs) {
        this.serverAddr = require(serverAddr, "serverAddr");
        this.serverPort = serverPort;
        this.token = require(token, "token");
        this.stunServer = require(stunServer, "stunServer");
        this.roomName = require(roomName, "roomName");
        this.secretKey = require(secretKey, "secretKey");
        this.punchTimeoutMs = punchTimeoutMs;
    }

    private static String require(String v, String name) {
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("凭证字段 " + name + " 不能为空");
        }
        return v;
    }

    public byte[] encode() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        try {
            out.writeByte(FORMAT_VERSION);
            out.writeUTF(serverAddr);
            out.writeInt(serverPort);
            out.writeUTF(token);
            out.writeUTF(stunServer);
            out.writeUTF(roomName);
            out.writeUTF(secretKey);
            out.writeInt(punchTimeoutMs);
            out.flush();
        } catch (IOException e) {
            // ByteArrayOutputStream 不会真的抛 IO 异常
            throw new IllegalStateException("编码凭证失败", e);
        }
        return buf.toByteArray();
    }

    /**
     * 解码。
     *
     * <p>版本号高于当前实现时仍会读取已知字段并忽略其余内容——服务端更新后
     * 老客户端仍能工作，只是用不上新特性，这比直接拒绝连接体面得多。
     *
     * @throws IOException 数据损坏或字段缺失
     */
    public static Credentials decode(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("凭证数据为空");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int version = in.readByte() & 0xFF;
        if (version < FORMAT_VERSION) {
            throw new IOException("凭证格式版本过旧: " + version);
        }
        String serverAddr = in.readUTF();
        int serverPort = in.readInt();
        String token = in.readUTF();
        String stunServer = in.readUTF();
        String roomName = in.readUTF();
        String secretKey = in.readUTF();
        int punchTimeoutMs = in.readInt();
        // 版本更高时后面可能还有字段，直接不读，保持向后兼容
        return new Credentials(serverAddr, serverPort, token, stunServer,
                roomName, secretKey, punchTimeoutMs);
    }

    public String serverAddr() {
        return serverAddr;
    }

    public int serverPort() {
        return serverPort;
    }

    public String token() {
        return token;
    }

    public String stunServer() {
        return stunServer;
    }

    public String roomName() {
        return roomName;
    }

    public String secretKey() {
        return secretKey;
    }

    /** 服务端建议的打洞超时；<=0 表示由客户端配置决定。 */
    public int punchTimeoutMs() {
        return punchTimeoutMs;
    }

    /** 刻意不输出 token 和 secretKey，避免日志泄露凭证。 */
    @Override
    public String toString() {
        return "Credentials{room=" + roomName + " frps=" + serverAddr + ":" + serverPort
                + " stun=" + stunServer + " punchTimeoutMs=" + punchTimeoutMs + "}";
    }
}
