package cn.ripplecraft.xtcpinmc.forge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.NetworkRegistry;

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

    @SidedProxy(clientSide = "cn.ripplecraft.xtcpinmc.forge.ClientProxy",
            serverSide = "cn.ripplecraft.xtcpinmc.forge.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ModConfig config = new ModConfig(event.getSuggestedConfigurationFile());
        FMLEventChannel channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);

        // 服务端半边。客户端上它也在（省一个 proxy 分支），但配置默认关闭，
        // 且单人游戏里 server 配置根本不会被填，不会有实际动作。
        FMLCommonHandler.instance().bus().register(new CredentialSender(channel, config));

        // 客户端半边只在物理客户端接线：专用服务器的类路径上没有任何 client 类
        proxy.initClient(channel, config);
    }
}
