package cn.ripplecraft.xtcpinmc.forge;

import cn.ripplecraft.xtcpinmc.core.CredentialCache;
import cn.ripplecraft.xtcpinmc.core.UpgradeController;
import cn.ripplecraft.xtcpinmc.core.WarmupController;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.FMLEventChannel;

/** 物理客户端的接线：把 core 的状态机挂到 Forge 的事件与频道上。 */
public final class ClientProxy extends CommonProxy {

    @Override
    public void initClient(FMLEventChannel channel, ModConfig config) {
        if (!config.clientEnabled()) {
            // 玩家可彻底关掉：不注册任何监听，连凭证都不收
            return;
        }
        ForgeClientBridge bridge = new ForgeClientBridge(channel, config.verboseLogging());
        CredentialCache cache = new CredentialCache(
                bridge.cacheDirectory().resolve("credentials"));
        WarmupController warmup = new WarmupController(bridge, cache, config.clientTimings(),
                new DirectServerEntry(bridge, config.directEntryName()),
                config.prewarmPort());
        UpgradeController controller = new UpgradeController(
                bridge, config.clientTimings(), cache, warmup);
        ClientEvents events = new ClientEvents(controller, warmup, bridge);

        // 凭证包走频道自己的事件总线，tick 与连接事件走 FML 总线
        channel.register(events);
        FMLCommonHandler.instance().bus().register(events);

        // FML 加载期就开始预热：GTNH 加载要几分钟，打洞只要几秒，
        // 到主菜单时直连条目已就绪。全程在后台线程，不碰加载主线程。
        if (config.clientPrewarm()) {
            warmup.start();
        }
    }
}
