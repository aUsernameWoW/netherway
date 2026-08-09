package cn.ripplecraft.netherway.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预认证的服务端半边：在玩家进服之前，把直连凭证换给一个能证明身份的客户端。
 *
 * <p>这是 {@link CredentialSender 登录后下发} 的补充路径——它解决的是先有鸡
 * 还是先有蛋：玩家第一次启动、或密钥轮换之后，本地没有任何可用凭证，而
 * 预热打洞需要凭证才能开始。走一遍皮肤站自己的 join/hasJoined，就能在不
 * 登录游戏的前提下证明「这是个真实账号」。
 *
 * <p>整个交换在 Minecraft 端口上完成（见 {@link PreauthProtocol}），
 * 不另开监听端口、不走 HTTP。唯一的对外请求是服务端主动发给皮肤站的
 * hasJoined 查证——皮肤站本就有公网地址，这条链路走 HTTPS。
 *
 * <p>这个类不碰任何 Minecraft 类型：在线模式、白名单、凭证与签发密钥
 * 都经 {@link Host} 由平台层供给。
 */
public final class PreauthService {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** hasJoined 的连接与读取超时。皮肤站慢的时候宁可放弃本轮，不能挂住线程。 */
    private static final int HTTP_TIMEOUT_MS = 10_000;

    /** 只读响应的前若干字节：Profile JSON 只有几百字节，防的是异常大的响应体。 */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /** 平台层需要供给的东西。实现要能被任意线程调用。 */
    public interface Host {

        /**
         * 服务器是否处于在线模式。false 时没有会话服务器可查证，
         * 交换退化为 {@link PreauthProtocol#MODE_OFFLINE}。
         */
        boolean onlineMode();

        /**
         * 皮肤站 API root（yggdrasil 形状，如 {@code https://skin.example.com/api/yggdrasil}）。
         * 在线模式下为空则无法查证，交换会被拒绝。
         */
        String authServer();

        /**
         * 这个玩家是否被允许换取凭证。平台层据服务器自己的准入名单判定
         * （白名单开着就查白名单，没开就一律放行）——谁能进服由 MC 服务端
         * 自己决定，本服务不另建一套。
         */
        boolean allowsPlayer(String username, String uuid);

        /** 要下发的房间凭证；配置不完整返回 null。 */
        Credentials credentials();

        /** 每玩家令牌的签发密钥，空串表示不签发。 */
        String tokenSigningKey();

        /** 每玩家令牌的有效天数。 */
        int tokenTtlDays();

        /** 诊断日志。参数已经过校验，可以安全进日志。 */
        void log(String message);
    }

    private final Host host;
    private final SecureRandom random = new SecureRandom();

    public PreauthService(Host host) {
        this.host = host;
    }

    /** 一次交换的结果：要么是成功的 payload，要么是拒绝原因。 */
    public static final class Reply {
        public final boolean ok;
        public final byte[] payload;
        public final String reason;

        private Reply(boolean ok, byte[] payload, String reason) {
            this.ok = ok;
            this.payload = payload;
            this.reason = reason;
        }

        static Reply ok(byte[] payload) {
            return new Reply(true, payload, null);
        }

        static Reply err(String reason) {
            return new Reply(false, null, reason);
        }

        /** 编码成可直接写回连接的响应帧。 */
        public byte[] encode() {
            return ok ? PreauthProtocol.encodeResponse(PreauthProtocol.STATUS_OK, payload)
                    : PreauthProtocol.errorResponse(reason);
        }
    }

    /**
     * 处理 HELLO：签一个 serverId 给客户端，并告诉它该去哪个皮肤站报到。
     *
     * @param outServerId 出参，签出的 serverId 写在这里，由调用方留在连接状态上
     */
    public Reply handleHello(String username, String uuid, StringBuilder outServerId) {
        String bad = PreauthProtocol.validateIdentity(username, uuid);
        if (!bad.isEmpty()) {
            return Reply.err(bad);
        }
        boolean online = host.onlineMode();
        String authServer = host.authServer() == null ? "" : host.authServer().trim();
        if (online && authServer.isEmpty()) {
            // 在线模式却不知道去哪查证：拒绝比签发一个查不了的 serverId 诚实
            return Reply.err("服务端未配置皮肤站地址，无法预认证");
        }
        String serverId = randomServerId();
        outServerId.setLength(0);
        outServerId.append(serverId);
        host.log("预认证: " + username + " (" + uuid + ") 领取 serverId，模式 "
                + (online ? "online" : "offline"));
        try {
            return Reply.ok(PreauthProtocol.encodeHelloReply(
                    online ? PreauthProtocol.MODE_ONLINE : PreauthProtocol.MODE_OFFLINE,
                    serverId, online ? authServer : ""));
        } catch (IOException e) {
            return Reply.err("组装应答失败");
        }
    }

    /**
     * 处理 CONFIRM：查证身份后签发令牌、组装凭证。
     *
     * @param issuedServerId 本连接上 HELLO 时签出的 serverId；客户端报的必须与它一致
     */
    public Reply handleConfirm(String issuedServerId, String serverId,
                               String username, String uuid) {
        String bad = PreauthProtocol.validateIdentity(username, uuid);
        if (!bad.isEmpty()) {
            return Reply.err(bad);
        }
        if (!PreauthProtocol.validServerId(serverId)) {
            return Reply.err("serverId 不合法");
        }
        // serverId 是本连接上签出的那一个：客户端不能拿别处的 serverId 来兑换。
        // 状态就在连接上，不需要任何全局表，连接一断自然清掉。
        if (issuedServerId == null || !issuedServerId.equals(serverId)) {
            return Reply.err("serverId 与本连接不符（请先发 HELLO）");
        }
        if (!host.allowsPlayer(username, uuid)) {
            host.log("预认证: 拒绝 " + username + "（不在服务器准入名单内）");
            return Reply.err("不在服务器准入名单内");
        }

        if (host.onlineMode()) {
            String authServer = host.authServer() == null ? "" : host.authServer().trim();
            if (authServer.isEmpty()) {
                return Reply.err("服务端未配置皮肤站地址，无法预认证");
            }
            String profileId;
            try {
                profileId = hasJoined(authServer, username, serverId);
            } catch (IOException e) {
                host.log("预认证: " + username + " hasJoined 失败: " + e.getMessage());
                return Reply.err("验证失败: " + e.getMessage());
            }
            // 防「用别人的 serverId + 自己的 username」骗凭证：比对 uuid
            if (!PreauthProtocol.normalizeUuid(profileId)
                    .equalsIgnoreCase(PreauthProtocol.normalizeUuid(uuid))) {
                host.log("预认证: " + username + " uuid 与皮肤站返回的不符");
                return Reply.err("uuid 不匹配");
            }
        }

        Credentials cred = host.credentials();
        if (cred == null) {
            return Reply.err("服务端凭证配置不完整");
        }
        cred = withPlayerToken(cred, uuid);
        host.log("预认证: " + username + " (" + uuid + ") 通过，已下发房间 "
                + cred.room() + " 的凭证");
        try {
            return Reply.ok(PreauthProtocol.encodeConfirmReply(
                    cred.room(), cred.backendId(), cred.encode()));
        } catch (IOException e) {
            return Reply.err("组装凭证失败");
        }
    }

    /**
     * 附加绑定该玩家 UUID、带有效期的身份参数。与
     * {@code CredentialSender.withPlayerToken} 是同一套规则——登录后下发与
     * 预认证下发必须签出同样形状的令牌，否则 authplugin 两边行为不一致。
     */
    private Credentials withPlayerToken(Credentials cred, String uuid) {
        String key = host.tokenSigningKey();
        if (key == null || key.isEmpty()) {
            return cred;
        }
        long expiry = System.currentTimeMillis() / 1000L + host.tokenTtlDays() * 86400L;
        Map<String, String> extra = new LinkedHashMap<String, String>();
        extra.put(Credentials.PARAM_USER, uuid);
        extra.put(Credentials.PARAM_USER_TOKEN, TokenIssuer.issue(key, uuid, expiry));
        return cred.withExtraParams(extra);
    }

    /**
     * 调皮肤站的 hasJoined，返回其 Profile 里的 id。
     *
     * <p>路径与 Mojang 原生一致。authlib-injector 是靠字节码替换把
     * {@code sessionserver.mojang.com} 换成皮肤站的，本服务不经它，
     * 所以手动拼完整路径。
     */
    private static String hasJoined(String authServer, String username, String serverId)
            throws IOException {
        String base = authServer.endsWith("/")
                ? authServer.substring(0, authServer.length() - 1) : authServer;
        String url = base + "/sessionserver/session/minecraft/hasJoined?username="
                + URLEncoder.encode(username, "UTF-8")
                + "&serverId=" + URLEncoder.encode(serverId, "UTF-8");

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                // 204/403 都表示「没人 join 过」——可能是客户端没调 join，
                // 或调了但 accessToken 无效被皮肤站拒了
                throw new IOException("皮肤站返回 " + code + "（玩家未 join 或 token 无效）");
            }
            String body = readBody(conn);
            Map<String, String> profile;
            try {
                // Profile 必带 properties 嵌套数组（textures 材质），
                // 用顶层解析跳过它——这里只需要 id
                profile = Json.parseTopLevel(body);
            } catch (RuntimeException e) {
                throw new IOException("解析 Profile 失败");
            }
            String id = profile.get("id");
            if (id == null || id.isEmpty()) {
                throw new IOException("Profile 缺少 id");
            }
            return id;
        } finally {
            conn.disconnect();
        }
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        InputStream in = conn.getInputStream();
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("皮肤站响应过大");
                }
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }

    /** 32 字符 hex 随机串，与 MC 服务端自己用的 serverId 形状一致。 */
    private String randomServerId() {
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
