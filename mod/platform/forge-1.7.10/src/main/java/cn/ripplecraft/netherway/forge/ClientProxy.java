package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.BridgeDiscovery;
import cn.ripplecraft.netherway.core.CredentialCache;
import cn.ripplecraft.netherway.core.Prefetcher;
import cn.ripplecraft.netherway.core.SessionIdentity;
import cn.ripplecraft.netherway.core.UpgradeController;
import cn.ripplecraft.netherway.core.WarmupController;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.FMLEventChannel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.util.Session;

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
                config.prewarmPort(), buildPrefetcher(bridge, config));
        UpgradeController controller = new UpgradeController(
                bridge, config.clientTimings(), cache, warmup);
        ClientEvents events = new ClientEvents(controller, warmup, bridge);

        // 凭证包走频道自己的事件总线，tick 与连接事件走 FML 总线
        channel.register(events);
        FMLCommonHandler.instance().bus().register(events);

        // FML 加载期就开始预热：GTNH 加载要几分钟，预取加打洞只要几秒，
        // 到主菜单时直连条目已就绪。全程在后台线程，不碰加载主线程。
        if (config.clientPrewarm()) {
            warmup.start();
        }
    }

    /**
     * 组装凭证预取器；缺任何前提（关了开关、离线会话、推不出候选地址）
     * 返回 null，预热退回「只用缓存凭证」的路径。
     */
    private static Prefetcher buildPrefetcher(ForgeClientBridge bridge, ModConfig config) {
        if (!config.clientPrefetch()) {
            return null;
        }
        Session session = Minecraft.getMinecraft().getSession();
        SessionIdentity id = session == null ? SessionIdentity.of("", "", "")
                : SessionIdentity.of(session.getUsername(),
                        session.getPlayerID(), session.getToken());
        if (!id.usable()) {
            bridge.debug("游戏会话不是有效的正版登录（离线/演示模式），跳过凭证预取");
            return null;
        }
        List<String> candidates = BridgeDiscovery.candidates(
                config.prefetchBridge(), serverListAddresses(bridge));
        if (candidates.isEmpty()) {
            bridge.debug("没有 authbridge 候选（未配置 client.prefetchBridge，"
                    + "服务器列表也推导不出），跳过凭证预取");
            return null;
        }
        bridge.debug("authbridge 候选（依次探测 /info）: " + candidates);
        return new Prefetcher(bridge, id, candidates, config.clientTimings());
    }

    /** 读服务器列表（server.dat）里的条目地址，读不了就当没有。 */
    private static List<String> serverListAddresses(ForgeClientBridge bridge) {
        List<String> out = new ArrayList<String>();
        try {
            ServerList list = new ServerList(Minecraft.getMinecraft());
            for (int i = 0; i < list.countServers(); i++) {
                ServerData entry = list.getServerData(i);
                if (entry != null && entry.serverIP != null) {
                    out.add(entry.serverIP);
                }
            }
        } catch (RuntimeException e) {
            bridge.warn("读取服务器列表失败，跳过 server.dat 扫描", e);
        }
        return out;
    }
}
