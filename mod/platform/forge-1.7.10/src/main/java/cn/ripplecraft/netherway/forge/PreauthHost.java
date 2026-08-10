package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.PreauthService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 把 {@link PreauthService} 需要的东西从配置里取出来。
 *
 * <p>预下发不做身份验证——准入交给 MC 服务端自己的白名单与正版验证，
 * 本服务只管把凭证送出去。
 */
final class PreauthHost implements PreauthService.Host {

    private static final Logger LOG = LogManager.getLogger(Netherway.MODID);

    private final ModConfig config;

    PreauthHost(ModConfig config) {
        this.config = config;
    }

    @Override
    public Credentials credentials() {
        return config.serverCredentials();
    }

    @Override
    public String tokenSigningKey() {
        return config.tokenSigningKey();
    }

    @Override
    public int tokenTtlDays() {
        return config.tokenTtlDays();
    }

    @Override
    public void log(String message) {
        LOG.info(message);
    }
}
