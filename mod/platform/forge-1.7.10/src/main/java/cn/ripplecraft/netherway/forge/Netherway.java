package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.Credentials;
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
            logPreauthConfig(host);
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
            LOG.warn("无法确定 Minecraft 监听端口，内置 serve 未启动"
                    + "（可用 server.localPort 显式指定）");
            return;
        }
        serverAgent = new ServerAgent(config);
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
        if (!config.serverRendezvous()) {
            return 0;
        }
        if (!config.serverRunAgent()) {
            LOG.warn("server.rendezvous 需要 server.runAgent=true："
                    + "会合点起在内置 serve 进程里，独立运行的 serve 不会开它。"
                    + "本次按不启用处理");
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
            LOG.info("内嵌会合点将监听 127.0.0.1:{}：玩家的 frp 控制连接会从 Minecraft "
                    + "端口转发进去，公网侧只需要一条能到 Minecraft 端口的 TCP 隧道", port);
            return port;
        } catch (java.io.IOException e) {
            LOG.warn("挑选会合点端口失败，内嵌会合点未启用", e);
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

    /**
     * 把预认证的生效形态写进启动日志。这一条尤其值得打：能不能预认证、
     * 拿什么查证、谁放得进来，三者都由运行环境决定，出问题时第一眼就要能看见。
     */
    private static void logPreauthConfig(PreauthHost host) {
        if (host.onlineMode()) {
            if (host.authServer().isEmpty()) {
                LOG.warn("预认证已开启但不知道皮肤站地址：请填 server.authServer，"
                        + "或用 -javaagent 挂 authlib-injector（会自动读出来）。"
                        + "在此之前玩家仍须先经中转进服拿凭证");
            } else {
                LOG.info("预认证已开启：在线模式，经 {} 查证 hasJoined", host.authServer());
            }
        } else {
            // online-mode=false 时没有会话服务器可查证。这不是本 mod 的选择，
            // 而是服务器本身就不验证身份——照搬它的准入判断即可。
            LOG.info("预认证已开启：服务器是 online-mode=false，无法查证正版身份，"
                    + "准入沿用服务器自己的名单（白名单开着就查白名单，否则一律放行）");
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
                    cn.ripplecraft.netherway.core.TokenIssuer.keyFingerprint(
                            config.tokenSigningKey()));
        }
        if (config.serverRunAgent()) {
            LOG.info("将随服务端启动内置 serve，把房间 \"{}\" 注册到 frps"
                    + "（若宿主机还单独跑着 netherway serve，请停掉其一，"
                    + "同名代理会注册冲突）", cred.room());
        } else {
            LOG.info("注意：server.runAgent 已关闭，mod 只下发凭证；房间 \"{}\" 的"
                    + "代理需要宿主机上独立运行的 netherway serve 注册到 frps"
                    + "（-room 必须一致），否则玩家侧会报 xtcp server doesn't exist",
                    cred.room());
        }
    }
}
