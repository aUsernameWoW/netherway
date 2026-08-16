package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.L10n;
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
@Mod(modid = Netherway.MODID, name = "Netherway", version = Tags.VERSION,
        acceptedMinecraftVersions = "[1.7.10]", acceptableRemoteVersions = "*")
public final class Netherway {

    public static final String MODID = "netherway";

    /**
     * 自定义频道名。走 Minecraft 原生的 plugin channel 机制，
     * 所以 Bukkit/Sponge 插件将来也能在同名频道上下发凭证。
     * 注意 1.7.10 的频道名上限是 20 字符。
     */
    public static final String CHANNEL = "netherway";

    private static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "cn.ripplecraft.netherway.forge.ClientProxy",
            serverSide = "cn.ripplecraft.netherway.forge.CommonProxy")
    public static CommonProxy proxy;

    private ModConfig config;
    private ServerAgent serverAgent;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        config = ModConfig.loadSafely(event.getSuggestedConfigurationFile());
        FMLEventChannel channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);

        // 服务端半边。客户端上它也在（省一个 proxy 分支），但只有真正启动
        // 服务端生命周期后才会工作；单纯启动客户端、浏览服务器列表不会起 serve。
        FMLCommonHandler.instance().bus().register(new CredentialSender(channel, config));
        channel.register(new UpgradeReportReceiver(config));
        logServerConfig(config);

        // 客户端半边只在物理客户端接线：专用服务器的类路径上没有任何 client 类
        proxy.initClient(channel, config);
    }

    /** 服务器就绪后挂嗅探器、启动内置 serve：此时监听端点与端口都已确定。 */
    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        if (!config.serverEnabled()) {
            return;
        }
        // 预认证与剥头共用一个嗅探 handler（它们抢的是同一批首字节）。
        // 剥头与 runAgent 无关：独立运行的 serve 开着 -proxy-protocol 时同样需要。
        PreauthHost host = config.serverPreauth() ? new PreauthHost(config) : null;
        if (host != null) {
            logPreauthConfig();
        }
        // 会合点端口在这里挑定：嗅探器要往它转发，agent 要在它上面监听，
        // 两边必须是同一个数，所以由这一处统一决定再分别传下去。
        int rendezvousPort = resolveRendezvousPort();
        ConnectionSniffer.install(MinecraftServer.getServer(),
                host == null ? null : new cn.ripplecraft.netherway.core.PreauthService(host),
                !config.serveProxyProtocol().isEmpty(), rendezvousPort);
        if (!config.serverRunAgent()) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        int port = config.serverLocalPort() > 0
                ? config.serverLocalPort() : server.getServerPort();
        if (port <= 0) {
            LOG.warn(L10n.tr("fserver.noPort"));
            return;
        }
        // 服务端角色的匿名遥测：与客户端同一端点、同一 telemetry.* 开关，
        // 只上报 serve 进程的生命周期（path=serve），不涉及任何玩家数据。
        cn.ripplecraft.netherway.core.telemetry.TelemetryCollector telemetry =
                TelemetryWiring.collector(config,
                        cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment
                                .Role.DEDICATED_SERVER);
        cn.ripplecraft.netherway.core.telemetry.TelemetryFlusher.start(telemetry, 60L);
        serverAgent = new ServerAgent(config, telemetry);
        serverAgent.start(server.getFile("netherway").toPath(), port, rendezvousPort);
    }

    /**
     * 挑一个空闲的回环端口给内嵌会合点。返回 0 表示不启用。
     *
     * <p>让系统分配而不是写死：会合点只对本机可见，端口号本身没有对外意义，
     * 写死反而会和别的服务撞车。挑完即释放，随后由 agent 绑上——中间有极小的
     * 竞态窗口，但回环上的端口分配不会立刻复用，实践中足够。
     */
    private int resolveRendezvousPort() {
        // serverRendezvous() 已是生效值（ModConfig 里与 runAgent 一并判定并告警），
        // 这里不再重复检查——两处各判一次的话，将来改了条件很容易只改一处，
        // 而「凭证按会合点形态下发、会合点却没起」是全员打洞失败且极难看出的故障。
        if (!config.serverRendezvous()) {
            return 0;
        }
        try {
            java.net.ServerSocket probe = new java.net.ServerSocket(0, 1,
                    java.net.InetAddress.getByName("127.0.0.1"));
            int port;
            try {
                port = probe.getLocalPort();
            } finally {
                probe.close();
            }
            LOG.info(L10n.tr("fserver.rendezvousPort", port));
            return port;
        } catch (java.io.IOException e) {
            LOG.warn(L10n.tr("fserver.rendezvousPortFailed"), e);
            return 0;
        }
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        ConnectionSniffer.shutdown();
        if (serverAgent != null) {
            serverAgent.stop();
            serverAgent = null;
        }
    }

    /** 把预认证已开启这件事写进启动日志。 */
    private static void logPreauthConfig() {
        LOG.info(L10n.tr("fserver.preauthOn"));
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
            LOG.warn(L10n.tr("fserver.incompleteCred"));
            return;
        }
        // toString 只列参数键名，不含 token 与密钥值
        LOG.info(L10n.tr("fserver.enabled", cred));
        if (!config.tokenSigningKey().isEmpty()) {
            // 指纹与 frps 侧 authplugin 的启动日志核对，两侧一致才说明密钥没配岔
            LOG.info(L10n.tr("fserver.tokenIssuing",
                    config.tokenTtlDays(),
                    cn.ripplecraft.netherway.core.TokenIssuer.keyFingerprint(
                            config.tokenSigningKey())));
        }
        if (config.serverRunAgent()) {
            LOG.info(L10n.tr("fserver.willRunServe", cred.room()));
        } else {
            LOG.info(L10n.tr("fserver.noRunAgent", cred.room()));
        }
    }
}
