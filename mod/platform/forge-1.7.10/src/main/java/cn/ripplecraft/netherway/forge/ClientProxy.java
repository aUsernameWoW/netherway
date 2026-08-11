package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.CredentialCache;
import cn.ripplecraft.netherway.core.Prefetcher;
import cn.ripplecraft.netherway.core.ServerCandidates;
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
     * 组装凭证预取器；缺任何前提（关了开关、离线会话、没有候选地址）
     * 返回 null，预热退回「只用缓存凭证」的路径。
     */
    private static Prefetcher buildPrefetcher(ForgeClientBridge bridge, ModConfig config) {
        if (!config.clientPrefetch()) {
            return null;
        }
        Session session = Minecraft.getMinecraft().getSession();
        SessionIdentity id = session == null ? SessionIdentity.of("", "")
                : SessionIdentity.of(session.getUsername(),
                        session.getPlayerID());
        // 离线会话仍然可以预取：预下发不做身份验证。
        // 但会话得有个像样的用户名，否则连请求的字段校验都过不了。
        if (id.username().isEmpty()) {
            bridge.debug("游戏会话没有用户名，跳过凭证预取");
            return null;
        }
        // prefetchServers 为空时退回扫描服务器列表（server.dat）。
        // 候选都是玩家自己加的，没开预下发的服务器会直接断开，对客户端就是「不应答」。
        String[] configured = config.prefetchServers();
        List<String> fromServerList = configured.length == 0
                ? serverListAddresses(bridge) : null;
        if (configured.length == 0
                && (fromServerList == null || fromServerList.isEmpty())) {
            bridge.debug("没有可预取的服务器地址（client.prefetchServers 为空且服务器列表无可用条目），跳过凭证预取");
            return null;
        }
        List<ServerCandidates.Address> candidates =
                ServerCandidates.build(configured, fromServerList);
        if (candidates.isEmpty()) {
            bridge.debug("没有可预取的服务器地址，跳过凭证预取");
            return null;
        }
        bridge.debug("预取候选（依次尝试）: " + candidates);
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
