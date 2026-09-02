package cn.ripplecraft.netherway.modern;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.L10n;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 服务端下发凭证的逻辑，与 MC/loader 无关：入口在玩家登录时提供其 UUID，
 * 这里回一份编码好的凭证字节，入口负责经频道发出。
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
     * @param uuid       玩家 UUID 字符串（目前只用于保持与登录事件同一签名）
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
        // Credentials.toString 刻意只列参数键名，不含密钥值
        LOG.info(L10n.tr("fserver.delivered", playerName, cred));
        return cred.encode();
    }
}
