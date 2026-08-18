package cn.ripplecraft.netherway.modern;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.TokenIssuer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 服务端下发凭证的逻辑，与 MC/loader 无关：入口在玩家登录时提供其 UUID，
 * 这里回一份编码好的凭证字节（含每玩家令牌），入口负责经频道发出。
 *
 * <p>这是整套设计的安全基础——凭证不随客户端分发，能走到玩家登录事件的
 * 玩家必然已通过服务器既有的正版验证/白名单。
 */
public final class CredentialService {

    private static final Logger LOG = LogManager.getLogger(NetherwayModern.MODID);

    private final ModConfig config;
    /** 配置不完整只提醒一次，别每个玩家登录都刷一遍日志。 */
    private boolean warnedInvalid;

    public CredentialService(ModConfig config) {
        this.config = config;
    }

    public boolean enabled() {
        return config.serverEnabled();
    }

    /**
     * 为该玩家生成待下发的凭证字节；配置不完整返回 null（入口据此不发包）。
     *
     * @param uuid       玩家 UUID 字符串，用于绑定每玩家令牌
     * @param playerName 仅用于日志
     */
    public byte[] credentialsFor(String uuid, String playerName) {
        if (!config.serverEnabled()) {
            return null;
        }
        Credentials cred = config.serverCredentials();
        if (cred == null) {
            if (!warnedInvalid) {
                warnedInvalid = true;
                LOG.warn(L10n.tr("fserver.incompleteCredKeys"));
            }
            return null;
        }
        cred = withPlayerToken(cred, uuid);
        // Credentials.toString 刻意只列参数键名，不含 token 与密钥值
        LOG.info(L10n.tr("fserver.delivered", playerName, cred));
        return cred.encode();
    }

    /**
     * 启用令牌签发时，为该玩家附加绑定其 UUID、带有效期的身份参数。
     * 每次登录都重新签发——客户端覆盖缓存，等于自动续签。
     */
    private Credentials withPlayerToken(Credentials cred, String uuid) {
        String key = config.tokenSigningKey();
        if (key.isEmpty()) {
            return cred;
        }
        long expiry = System.currentTimeMillis() / 1000L + config.tokenTtlDays() * 86400L;
        Map<String, String> extra = new LinkedHashMap<String, String>();
        extra.put(Credentials.PARAM_USER, uuid);
        extra.put(Credentials.PARAM_USER_TOKEN, TokenIssuer.issue(key, uuid, expiry));
        return cred.withExtraParams(extra);
    }
}
