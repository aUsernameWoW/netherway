package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.AuthlibInjector;
import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.PreauthService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 把 {@link PreauthService} 需要的东西从 Minecraft 服务端取出来。
 *
 * <p>core 不碰 Minecraft 类型，所以「在线模式」「白名单」这两项只能在这一层拿。
 *
 * <p><b>准入沿用服务器自己的名单，不另立一套。</b>白名单开着就查白名单，
 * 没开就一律放行——谁能进服本来就由 MC 服务端决定，凭证换来的隧道也只
 * 通向 MC 端口，本服务不该比服务器本身更严或更松。
 */
final class PreauthHost implements PreauthService.Host {

    private static final Logger LOG = LogManager.getLogger(Netherway.MODID);

    private final ModConfig config;
    private final String authServer;

    PreauthHost(ModConfig config) {
        this.config = config;
        this.authServer = resolveAuthServer(config.serverAuthServer());
    }

    @Override
    public boolean onlineMode() {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null && server.isServerInOnlineMode();
    }

    @Override
    public String authServer() {
        return authServer;
    }

    /**
     * 白名单判定。取不到服务器实例（理论上不会）时保守拒绝——宁可玩家退回
     * 中转进服，也不要在状态不明时把凭证发出去。
     */
    @Override
    public boolean allowsPlayer(String username, String uuid) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return false;
        }
        ServerConfigurationManager scm = server.getConfigurationManager();
        if (scm == null || !scm.isWhiteListEnabled()) {
            return true;
        }
        String[] names = scm.func_152599_k().func_152685_a();
        if (names == null) {
            return true;
        }
        for (String n : names) {
            if (n != null && n.equalsIgnoreCase(username)) {
                return true;
            }
        }
        return false;
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

    /**
     * 皮肤站 API root：配置显式给出的优先，否则从 authlib-injector 的
     * javaagent 参数里读——服务端本来就得挂它才能用第三方皮肤站，
     * 地址已经在命令行上，服主不必再抄一遍。
     */
    private static String resolveAuthServer(String configured) {
        String cfg = configured == null ? "" : configured.trim();
        if (!cfg.isEmpty()) {
            return cfg;
        }
        String detected = AuthlibInjector.detect();
        if (!detected.isEmpty()) {
            LOG.info("预认证: 皮肤站地址取自 authlib-injector 的启动参数 {}", detected);
        }
        return detected;
    }
}
