package cn.ripplecraft.xtcpinmc.forge;

import cn.ripplecraft.xtcpinmc.core.Credentials;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.NetworkRegistry;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forge 1.7.10 入口。
 *
 * <p>同一个 jar 同时装在服务端与客户端：服务端半边在玩家登录后下发凭证
 * （{@link CredentialSender}），客户端半边收到凭证后驱动 core 的升级流程
 * （{@link ClientProxy} 接线）。两边各自靠配置开关，装错地方不会出事。
 *
 * <p>{@code acceptableRemoteVersions = "*"}：没装 mod 的客户端照常进服，
 * 装了 mod 的客户端也能连没装 mod 的服务器——本 mod 是纯增强，
 * 绝不能变成准入门槛。
 */
@Mod(modid = XtcpInMc.MODID, name = XtcpInMc.MODID, version = Tags.VERSION,
        acceptedMinecraftVersions = "[1.7.10]", acceptableRemoteVersions = "*")
public final class XtcpInMc {

    public static final String MODID = "xtcpinmc";

    /**
     * 自定义频道名。走 Minecraft 原生的 plugin channel 机制，
     * 所以 Bukkit/Sponge 插件将来也能在同名频道上下发凭证。
     * 注意 1.7.10 的频道名上限是 20 字符。
     */
    public static final String CHANNEL = "xtcpinmc";

    private static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "cn.ripplecraft.xtcpinmc.forge.ClientProxy",
            serverSide = "cn.ripplecraft.xtcpinmc.forge.CommonProxy")
    public static CommonProxy proxy;

    private ModConfig config;
    private ServerAgent serverAgent;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        config = new ModConfig(event.getSuggestedConfigurationFile());
        FMLEventChannel channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);

        // 服务端半边。客户端上它也在（省一个 proxy 分支），但配置默认关闭，
        // 且单人游戏里 server 配置根本不会被填，不会有实际动作。
        FMLCommonHandler.instance().bus().register(new CredentialSender(channel, config));
        channel.register(new UpgradeReportReceiver(config));
        logServerConfig(config);

        // 客户端半边只在物理客户端接线：专用服务器的类路径上没有任何 client 类
        proxy.initClient(channel, config);
    }

    /** 服务器就绪后挂剥头组件、启动内置 serve：此时监听端点与端口都已确定。 */
    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (!config.serverEnabled()) {
            return;
        }
        // 剥头与 runAgent 无关：独立运行的 serve 开着 -proxy-protocol 时同样需要
        if (!config.serveProxyProtocol().isEmpty()) {
            ProxyProtocolInjector.install(MinecraftServer.getServer());
        }
        if (!config.serverRunAgent()) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        int port = config.serverLocalPort() > 0
                ? config.serverLocalPort() : server.getServerPort();
        if (port <= 0) {
            LOG.warn("无法确定 Minecraft 监听端口，内置 serve 未启动"
                    + "（可用 server.localPort 显式指定）");
            return;
        }
        serverAgent = new ServerAgent(config);
        serverAgent.start(server.getFile("xtcpinmc").toPath(), port);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        if (serverAgent != null) {
            serverAgent.stop();
            serverAgent = null;
        }
    }

    /**
     * 启动时把服务端半边的生效配置写进日志：出问题时第一眼就能对出
     * 「配置到底读进来没有、读成了什么」，而不是等第一个玩家登录才知道。
     */
    private static void logServerConfig(ModConfig config) {
        if (!config.serverEnabled()) {
            return;
        }
        Credentials cred = config.serverCredentials();
        if (cred == null) {
            LOG.warn("server.enabled 已开启但凭证配置不完整，不会下发直连凭证"
                    + "（检查 server.params）");
            return;
        }
        // toString 只列参数键名，不含 token 与密钥值
        LOG.info("服务端直连已启用，玩家登录后将下发 {}", cred);
        if (!config.tokenSigningKey().isEmpty()) {
            // 指纹与 frps 侧 authplugin 的启动日志核对，两侧一致才说明密钥没配岔
            LOG.info("每玩家令牌签发已启用（有效期 {} 天），签发密钥指纹 {}",
                    config.tokenTtlDays(),
                    cn.ripplecraft.xtcpinmc.core.TokenIssuer.keyFingerprint(
                            config.tokenSigningKey()));
        }
        if (config.serverRunAgent()) {
            LOG.info("将随服务端启动内置 serve，把房间 \"{}\" 注册到 frps"
                    + "（若宿主机还单独跑着 xtcpinmc serve，请停掉其一，"
                    + "同名代理会注册冲突）", cred.room());
        } else {
            LOG.info("注意：server.runAgent 已关闭，mod 只下发凭证；房间 \"{}\" 的"
                    + "代理需要宿主机上独立运行的 xtcpinmc serve 注册到 frps"
                    + "（-room 必须一致），否则玩家侧会报 xtcp server doesn't exist",
                    cred.room());
        }
    }
}
