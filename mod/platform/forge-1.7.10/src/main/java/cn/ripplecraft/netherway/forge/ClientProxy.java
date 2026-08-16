package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.CredentialCache;
import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.Prefetcher;
import cn.ripplecraft.netherway.core.ServerCandidates;
import cn.ripplecraft.netherway.core.SessionIdentity;
import cn.ripplecraft.netherway.core.UpgradeController;
import cn.ripplecraft.netherway.core.WarmupController;
import cn.ripplecraft.netherway.core.telemetry.QualitySummary;
import cn.ripplecraft.netherway.core.telemetry.TelemetryCollector;
import cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment;
import cn.ripplecraft.netherway.core.telemetry.TelemetryFlusher;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.FMLEventChannel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.util.Session;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.ClientCommandHandler;

/** 物理客户端的接线：把 core 的状态机挂到 Forge 的事件与频道上。 */
public final class ClientProxy extends CommonProxy {

    private TelemetryCollector telemetry;

    @Override
    public void initClient(FMLEventChannel channel, ModConfig config) {
        // cfg 的 language=auto 在客户端精化为跟随游戏语言（zh_CN/en_US 等）。
        // 玩家改游戏语言后要重启才生效——消息目录在各后台线程被读取，不追热切换。
        if ("auto".equalsIgnoreCase(config.language())) {
            try {
                String gameLang = Minecraft.getMinecraft().gameSettings.language;
                if (gameLang != null && !gameLang.isEmpty()) {
                    L10n.use(gameLang);
                }
            } catch (RuntimeException ignored) {
                // 读不到就维持系统 locale 的判定
            }
        }
        telemetry = TelemetryWiring.collector(config, TelemetryEnvironment.Role.CLIENT);
        TelemetryFlusher.start(telemetry, 60L);
        if (!config.clientEnabled()) {
            // 玩家可彻底关掉：不注册任何监听，连凭证都不收
            return;
        }
        ForgeClientBridge bridge = new ForgeClientBridge(channel, config.verboseLogging());
        ClientCommandHandler.instance.registerCommand(new TelemetryCommand(telemetry, bridge));
        CredentialCache cache = new CredentialCache(
                bridge.cacheDirectory().resolve("credentials"));
        WarmupEntryRouter entryRouter = new WarmupEntryRouter(
                config.replaceServerEntries(),
                new DirectServerEntry(bridge, config.directEntryName()), bridge);
        WarmupController warmup = new WarmupController(bridge, cache, config.clientTimings(),
                entryRouter,
                config.prewarmPort(), buildPrefetcher(bridge, config, telemetry), telemetry);
        UpgradeController controller = new UpgradeController(
                bridge, config.clientTimings(), cache, warmup, telemetry);
        controller.setRedirectOnWarmReady(config.redirectOnWarmReady());
        ClientEvents events = new ClientEvents(controller, warmup, bridge);

        // 凭证包走频道自己的事件总线，tick 与连接事件走 FML 总线
        channel.register(events);
        FMLCommonHandler.instance().bus().register(events);
        if (entryRouter.replacesEntries() && config.clientPrewarm()) {
            MinecraftForge.EVENT_BUS.register(new RouteAwareGuiHandler(entryRouter, bridge));
        }

        // FML 加载期就开始预热：短 TCP 预取在后台有界并行，真正的 NAT 打洞
        // 严格串行；都不碰加载主线程，已建立的多条隧道则可同时守望。
        if (config.clientPrewarm()) {
            warmup.start();
        }
    }

    /**
     * 组装凭证预取器；缺任何前提（关了开关、离线会话、没有候选地址）
     * 返回 null，预热退回「只用缓存凭证」的路径。
     */
    private static Prefetcher buildPrefetcher(ForgeClientBridge bridge, ModConfig config,
                                              TelemetryCollector telemetry) {
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
            bridge.debug(L10n.tr("fclient.noUsername"));
            return null;
        }
        // prefetchServers 为空时退回扫描服务器列表（server.dat）。
        // 候选都是玩家自己加的，没开预下发的服务器会直接断开，对客户端就是「不应答」。
        String[] configured = config.prefetchServers();
        List<String> fromServerList = configured.length == 0
                ? serverListAddresses(bridge) : null;
        if (configured.length == 0
                && (fromServerList == null || fromServerList.isEmpty())) {
            bridge.debug(L10n.tr("fclient.noPrefetchTargets.detail"));
            return null;
        }
        List<ServerCandidates.Address> candidates =
                ServerCandidates.build(configured, fromServerList);
        if (candidates.isEmpty()) {
            bridge.debug(L10n.tr("fclient.noPrefetchTargets"));
            return null;
        }
        bridge.debug(L10n.tr("fclient.prefetchCandidates", candidates));
        QualitySummary.Source source = configured.length == 0
                ? QualitySummary.Source.SERVER_LIST : QualitySummary.Source.CONFIG;
        return new Prefetcher(bridge, id, candidates, config.clientTimings(), source, telemetry);
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
            bridge.warn(L10n.tr("fclient.serverListReadFailed"), e);
        }
        return out;
    }
}
