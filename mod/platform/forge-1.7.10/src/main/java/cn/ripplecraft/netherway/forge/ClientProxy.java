package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.CredentialCache;
import cn.ripplecraft.netherway.core.Prefetcher;
import cn.ripplecraft.netherway.core.Platform;
import cn.ripplecraft.netherway.core.ServerCandidates;
import cn.ripplecraft.netherway.core.SessionIdentity;
import cn.ripplecraft.netherway.core.UpgradeController;
import cn.ripplecraft.netherway.core.WarmupController;
import cn.ripplecraft.netherway.core.telemetry.QualitySummary;
import cn.ripplecraft.netherway.core.telemetry.HttpTelemetryTransport;
import cn.ripplecraft.netherway.core.telemetry.TelemetryCollector;
import cn.ripplecraft.netherway.core.telemetry.TelemetryConfig;
import cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment;
import cn.ripplecraft.netherway.core.telemetry.TelemetryFlusher;
import cn.ripplecraft.netherway.core.telemetry.TelemetryTransport;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.FMLEventChannel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.util.Session;
import net.minecraftforge.client.ClientCommandHandler;

/** 物理客户端的接线：把 core 的状态机挂到 Forge 的事件与频道上。 */
public final class ClientProxy extends CommonProxy {

    private static final String TELEMETRY_ENDPOINT =
            "https://telemetry.ripplecraft.cn/v1/batches";

    private TelemetryCollector telemetry;

    @Override
    public void initClient(FMLEventChannel channel, ModConfig config) {
        TelemetryTransport transport = new HttpTelemetryTransport(
                TELEMETRY_ENDPOINT, 2_000, 2_000);
        telemetry = new TelemetryCollector(
                new TelemetryConfig(config.telemetryEnabled(), config.telemetryEnhanced(),
                        TelemetryConfig.DEFAULT_MAX_PENDING),
                clientTelemetryEnvironment(), transport);
        TelemetryFlusher.start(telemetry, 60L);
        if (!config.clientEnabled()) {
            // 玩家可彻底关掉：不注册任何监听，连凭证都不收
            return;
        }
        ForgeClientBridge bridge = new ForgeClientBridge(channel, config.verboseLogging());
        ClientCommandHandler.instance.registerCommand(new TelemetryCommand(telemetry, bridge));
        CredentialCache cache = new CredentialCache(
                bridge.cacheDirectory().resolve("credentials"));
        WarmupController warmup = new WarmupController(bridge, cache, config.clientTimings(),
                new DirectServerEntry(bridge, config.directEntryName()),
                config.prewarmPort(), buildPrefetcher(bridge, config, telemetry), telemetry);
        UpgradeController controller = new UpgradeController(
                bridge, config.clientTimings(), cache, warmup, telemetry);
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
        QualitySummary.Source source = configured.length == 0
                ? QualitySummary.Source.SERVER_LIST : QualitySummary.Source.CONFIG;
        return new Prefetcher(bridge, id, candidates, config.clientTimings(), source, telemetry);
    }

    /** 只输出低基数的标准化环境值；绝不把原始系统属性塞进 payload。 */
    private static TelemetryEnvironment clientTelemetryEnvironment() {
        String os = "other";
        String arch = "other";
        try {
            String platform = Platform.detect().toString();
            int dash = platform.indexOf('-');
            if (dash > 0) {
                os = platform.substring(0, dash);
                arch = platform.substring(dash + 1);
            }
        } catch (Platform.UnsupportedPlatformException ignored) {
            // Unsupported platforms are deliberately grouped as other/other.
        }
        return new TelemetryEnvironment(Tags.VERSION, "1.7.10", javaMajor(), os, arch,
                TelemetryEnvironment.Role.CLIENT);
    }

    private static String javaMajor() {
        String spec = System.getProperty("java.specification.version", "");
        if (spec.startsWith("1.")) {
            spec = spec.substring(2);
        }
        int dot = spec.indexOf('.');
        return dot < 0 ? spec : spec.substring(0, dot);
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
