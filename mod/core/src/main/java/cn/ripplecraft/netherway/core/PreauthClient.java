package cn.ripplecraft.netherway.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * 预认证的客户端半边：向 Minecraft 服务器端口要一份直连凭证。
 *
 * <p>与服务端的对话全在那一个 MC 端口上完成（{@link PreauthProtocol}），
 * 中途只有一次出门：拿 accessToken 去皮肤站 {@code /join} 报到。皮肤站
 * 本来就有公网地址（正版和 authlib-injector 都一样），这一跳强制 HTTPS。
 *
 * <p><b>accessToken 只在本机与皮肤站之间走</b>，从不进入与 MC 服务器的任何
 * 一帧——服务端只会收到「某人用 serverId 报过到」的结果，拿不到令牌本身。
 *
 * <p>整个交换用一条 TCP 连接：serverId 是服务端在这条连接上签出并记住的，
 * 客户端拿别处的 serverId 换不到东西，服务端也因此不需要任何全局状态。
 */
public final class PreauthClient {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 皮肤站 join 的连接与读取超时。 */
    private static final int HTTP_TIMEOUT_MS = 10_000;

    private final ClientBridge bridge;
    /** 本机自己认的皮肤站；非空时服务端说什么都不算数。 */
    private final String pinnedAuthServer;

    public PreauthClient(ClientBridge bridge, String pinnedAuthServer) {
        this.bridge = bridge;
        this.pinnedAuthServer = pinnedAuthServer == null ? "" : pinnedAuthServer.trim();
    }

    /**
     * 向一个候选地址换取凭证。
     *
     * @param connectTimeoutMs TCP 连接与读取的超时
     * @return 换到的凭证；这个地址不提供预认证、或验证没通过时抛异常
     */
    public Credentials fetch(ServerCandidates.Address addr, SessionIdentity session,
                             int connectTimeoutMs) throws IOException {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(addr.host, addr.port), connectTimeoutMs);
            sock.setSoTimeout(connectTimeoutMs);
            OutputStream out = sock.getOutputStream();
            DataInputStream in = new DataInputStream(sock.getInputStream());

            // 1. HELLO：自报身份，换 serverId 与皮肤站地址
            out.write(PreauthProtocol.encodeRequest(PreauthProtocol.OP_HELLO,
                    PreauthProtocol.encodeHello(session.username(), session.uuid())));
            out.flush();
            DataInputStream hello = new DataInputStream(readPayload(in));
            int mode = hello.readByte() & 0xFF;
            String serverId = hello.readUTF();
            String authServer = hello.readUTF();

            // 2. 在线模式才需要去皮肤站报到；离线服务器无从查证，直接进第 3 步
            if (mode == PreauthProtocol.MODE_ONLINE) {
                authServer = resolveAuthServer(authServer);
                if (authServer.isEmpty()) {
                    throw new IOException("不知道该去哪个皮肤站报到");
                }
                if (!session.usable()) {
                    // 离线/演示会话拿不出有效 accessToken，join 必然被拒，
                    // 省掉一次注定失败的 HTTPS 往返
                    throw new IOException("服务器要求正版身份，但当前是离线会话");
                }
                join(authServer, session, serverId);
            } else {
                bridge.debug("服务器是 online-mode=false，跳过皮肤站报到");
            }

            // 3. CONFIRM：请服务端查证并下发凭证
            out.write(PreauthProtocol.encodeRequest(PreauthProtocol.OP_CONFIRM,
                    PreauthProtocol.encodeConfirm(serverId, session.username(),
                            session.uuid())));
            out.flush();
            DataInputStream confirm = new DataInputStream(readPayload(in));
            confirm.readUTF(); // room：凭证里也有，这里只是给人看的
            confirm.readUTF(); // backendId：同上
            int len = confirm.readInt();
            if (len <= 0 || len > PreauthProtocol.MAX_PAYLOAD) {
                throw new IOException("凭证长度不合法: " + len);
            }
            byte[] raw = new byte[len];
            confirm.readFully(raw);
            return Credentials.decode(raw);
        } finally {
            closeQuietly(sock);
        }
    }

    /**
     * 决定把 accessToken 发给谁。
     *
     * <p><b>本机认的皮肤站优先，服务端说的不算。</b>accessToken 只在本机
     * 登录的那个皮肤站上有效，服务端没有任何理由替我们指定它——而预取会
     * 去问服务器列表里的地址，其中可能有没打过交道的服务器，让它指定
     * 就等于把令牌交给它。本机挂了 authlib-injector 时这个洞直接封死。
     *
     * <p>本机读不出来（没挂 injector、或极简运行时没有 java.management）时
     * 才退回服务端告知的地址——那种情况多半是正版会话，
     * 而正版的皮肤站是 Mojang，服务端指错也换不到有效令牌。
     */
    private String resolveAuthServer(String fromServer) {
        if (!pinnedAuthServer.isEmpty()) {
            if (!fromServer.isEmpty() && !fromServer.equals(pinnedAuthServer)) {
                bridge.debug("服务端告知的皮肤站是 " + fromServer
                        + "，与本机登录用的不一致，按本机的来: " + pinnedAuthServer);
            }
            return pinnedAuthServer;
        }
        return fromServer;
    }

    /**
     * 读一个响应帧的 payload；服务端回了拒绝就抛异常，原因原样带出。
     */
    private static InputStream readPayload(DataInputStream in) throws IOException {
        int version = in.readUnsignedByte();
        int status = in.readUnsignedByte();
        int len = in.readUnsignedShort();
        if (len > PreauthProtocol.MAX_PAYLOAD) {
            throw new IOException("响应 payload 超限: " + len);
        }
        byte[] payload = new byte[len];
        in.readFully(payload);
        if (version != PreauthProtocol.VERSION) {
            throw new IOException("服务端协议版本 " + version
                    + " 与本机 " + PreauthProtocol.VERSION + " 不符");
        }
        if (status != PreauthProtocol.STATUS_OK) {
            String reason = "未说明";
            try {
                reason = new DataInputStream(
                        new java.io.ByteArrayInputStream(payload)).readUTF();
            } catch (IOException ignored) {
                // 拒绝原因读不出来也不影响「被拒绝」这个结论
            }
            throw new IOException("服务端拒绝: " + reason);
        }
        return new java.io.ByteArrayInputStream(payload);
    }

    /**
     * 拿 accessToken 去皮肤站报到。这是唯一接触 token 的一步，也是唯一
     * 离开「那一个端口」的一跳——皮肤站本就是公网服务，强制 HTTPS。
     */
    private void join(String authServer, SessionIdentity session, String serverId)
            throws IOException {
        String base = authServer.endsWith("/")
                ? authServer.substring(0, authServer.length() - 1) : authServer;
        if (!base.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            // accessToken 等价于登录态本身，绝不允许明文过网
            throw new IOException("皮肤站地址必须是 https（收到 " + base + "）");
        }
        URL url = new URL(base + "/sessionserver/session/minecraft/join");
        byte[] body = joinBody(session, serverId);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            OutputStream os = conn.getOutputStream();
            try {
                os.write(body);
                os.flush();
            } finally {
                os.close();
            }
            int code = conn.getResponseCode();
            // 皮肤站对成功的 join 回 204（无内容）；200 也当成功
            if (code != HttpURLConnection.HTTP_NO_CONTENT
                    && code != HttpURLConnection.HTTP_OK) {
                throw new IOException("皮肤站 join 返回 " + code + "（accessToken 无效？）");
            }
            bridge.debug("已向皮肤站报到，等待服务端查证");
        } finally {
            conn.disconnect();
        }
    }

    /** join 请求体。字段名与 Yggdrasil 一致。 */
    private static byte[] joinBody(SessionIdentity session, String serverId) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        appendField(sb, "accessToken", session.accessToken()).append(',');
        appendField(sb, "selectedProfile",
                PreauthProtocol.normalizeUuid(session.uuid())).append(',');
        appendField(sb, "serverId", serverId);
        sb.append('}');
        return sb.toString().getBytes(UTF8);
    }

    private static StringBuilder appendField(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"");
        escape(sb, value);
        return sb.append('"');
    }

    /**
     * JSON 字符串转义。手写的原因与 {@link Json} 相同：core 零第三方依赖。
     * 只需覆盖这三个字段可能出现的字符——但仍按通用规则处理，
     * 免得将来换了字段来源才发现漏掉。
     */
    static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        // 控制字符必须转成 6 字符转义序列，否则 JSON 非法
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // 关不掉也没什么可做的
        }
    }
}
