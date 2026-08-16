package cn.ripplecraft.netherway.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预下发的服务端半边：玩家进服之前把直连凭证发出去。
 *
 * <p>这是 {@link CredentialSender 登录后下发} 的补充路径——它解决的是先有鸡
 * 还是先有蛋：玩家第一次启动、或密钥轮换之后，本地没有任何可用凭证，而
 * 预热打洞需要凭证才能开始。
 *
 * <p><b>不做任何身份验证。</b>客户端来要就给。准入由 MC 服务端自己的白名单
 * 与正版验证保证——本服务只管把凭证送出去。
 *
 * <p>整个交换在 Minecraft 端口上完成（见 {@link PreauthProtocol}），
 * 不另开监听端口、不走 HTTP。
 */
public final class PreauthService {

    /** 平台层需要供给的东西。实现要能被任意线程调用。 */
    public interface Host {

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
     * 处理凭证请求：校验输入形状后直接签发凭证。
     *
     * <p>不做身份验证——username/uuid 只用于签发绑定该玩家的每玩家令牌
     * （如果服务端配了 tokenSigningKey）以及日志。真实性由 MC 服务端保证。
     */
    public Reply handleRequest(String username, String uuid) {
        String bad = PreauthProtocol.validateIdentity(username, uuid);
        if (!bad.isEmpty()) {
            return Reply.err(bad);
        }
        Credentials cred = host.credentials();
        if (cred == null) {
            return Reply.err(L10n.tr("preauth.incompleteConfig"));
        }
        cred = withPlayerToken(cred, uuid);
        host.log(L10n.tr("preauth.issued", username, uuid, cred.room()));
        try {
            return Reply.ok(cred.encode());
        } catch (RuntimeException e) {
            return Reply.err(L10n.tr("preauth.buildFailed"));
        }
    }

    /**
     * 附加绑定该玩家 UUID、带有效期的身份参数。与
     * {@code CredentialSender.withPlayerToken} 是同一套规则——登录后下发与
     * 预下发必须签出同样形状的令牌，否则 authplugin 两边行为不一致。
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
}
