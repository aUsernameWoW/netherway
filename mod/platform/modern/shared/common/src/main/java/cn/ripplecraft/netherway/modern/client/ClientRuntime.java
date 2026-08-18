package cn.ripplecraft.netherway.modern.client;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.CredentialCache;
import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.Prefetcher;
import cn.ripplecraft.netherway.core.ServerCandidates;
import cn.ripplecraft.netherway.core.SessionIdentity;
import cn.ripplecraft.netherway.core.UpgradeController;
import cn.ripplecraft.netherway.core.WarmupController;
import cn.ripplecraft.netherway.core.telemetry.QualitySummary;
import cn.ripplecraft.netherway.core.telemetry.TelemetryCollector;
import cn.ripplecraft.netherway.modern.ModConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.Connection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端接线，Forge/Fabric 共用。相当于 forge-1.7.10 的 ClientProxy.initClient
 * 与 ClientEvents 合体：构造 core 的状态机、预热器、预取器，并把入口喂进来的
 * 凭证包 / tick / 连接事件转成 controller 调用。
 *
 * <p>入口（各 loader）负责把 MC 事件绑到本类的 {@link #onCredentials}、
 * {@link #clientTick}、{@link #onConnected}、{@link #onDisconnected} 上。
 */
public final class ClientRuntime {

    private static final Logger LOG = LogManager.getLogger("netherway");

    private final ModernClientBridge bridge;
    private final UpgradeController controller;
    private final WarmupController warmup;

    private ClientRuntime(ModernClientBridge bridge, UpgradeController controller,
                          WarmupController warmup) {
        this.bridge = bridge;
        this.controller = controller;
        this.warmup = warmup;
    }

    public ModernClientBridge bridge() {
        return bridge;
    }

    /**
     * 装配整个客户端。{@code directFactory} 在关闭覆盖时按需构造独立直连
     * 条目的监听器（各版本自带，因 ServerData/ServerList API 逐版不同）；
     * 开启覆盖时不会被调用，可传 null。
     */
    public static ClientRuntime create(ModConfig config, TelemetryCollector telemetry,
                                       GameOps ops, ClientNetwork network,
                                       SessionIdentity session,
                                       DirectEntryFactory directFactory) {
        ModernClientBridge bridge = new ModernClientBridge(ops, network, config.verboseLogging());
        CredentialCache cache = new CredentialCache(
                bridge.cacheDirectory().resolve("credentials"));
        WarmupController.Listener directEntries =
                config.replaceServerEntries() || directFactory == null
                        ? null : directFactory.create(bridge, config.directEntryName());
        WarmupEntryRouter entryRouter = new WarmupEntryRouter(
                config.replaceServerEntries(), directEntries, bridge);
        WarmupController warmup = new WarmupController(bridge, cache, config.clientTimings(),
                entryRouter, config.prewarmPort(),
                buildPrefetcher(bridge, config, session, telemetry), telemetry);
        UpgradeController controller = new UpgradeController(
                bridge, config.clientTimings(), cache, warmup, telemetry);
        controller.setRedirectOnWarmReady(config.redirectOnWarmReady());

        // 覆盖模式下把路由表登记给 ConnectScreen/ServerStatusPinger 的 Mixin
        if (entryRouter.replacesEntries() && config.clientPrewarm()) {
            ClientRouting.install(entryRouter);
        }
        if (config.clientPrewarm()) {
            warmup.start();
        }
        return new ClientRuntime(bridge, controller, warmup);
    }

    /** 组装凭证预取器；缺任何前提返回 null，预热退回「只用缓存凭证」。 */
    private static Prefetcher buildPrefetcher(ModernClientBridge bridge, ModConfig config,
                                              SessionIdentity session,
                                              TelemetryCollector telemetry) {
        if (!config.clientPrefetch()) {
            return null;
        }
        if (session == null || session.username().isEmpty()) {
            bridge.debug(L10n.tr("fclient.noUsername"));
            return null;
        }
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
        return new Prefetcher(bridge, session, candidates, config.clientTimings(),
                source, telemetry);
    }

    /** 读服务器列表（servers.dat）里的条目地址，读不了就当没有。mojmap API 稳定。 */
    private static List<String> serverListAddresses(ModernClientBridge bridge) {
        List<String> out = new ArrayList<String>();
        try {
            ServerList list = new ServerList(Minecraft.getInstance());
            list.load();
            for (int i = 0; i < list.size(); i++) {
                ServerData entry = list.get(i);
                if (entry != null && entry.ip != null) {
                    out.add(entry.ip);
                }
            }
        } catch (RuntimeException e) {
            bridge.warn(L10n.tr("fclient.serverListReadFailed"), e);
        }
        return out;
    }

    // ---- 入口回调 ----

    /** 服务端在频道上发来了凭证。可能在 netty 线程。 */
    public void onCredentials(byte[] data) {
        bridge.debug(L10n.tr("fclient.credPacket", data.length));
        try {
            Credentials cred = Credentials.decode(data);
            controller.onCredentials(cred);
        } catch (IOException e) {
            LOG.warn(L10n.tr("fclient.credDecodeFailed"), e);
        }
    }

    /** 每客户端 tick 一次（入口在 tick START 调用）。 */
    public void clientTick() {
        if (bridge.redirectTimedOut()) {
            controller.onRedirectNotLanded();
            controller.shutdown();
        }
    }

    /** 新连接建立。 */
    public void onConnected(Connection connection) {
        ModernClientBridge.ConnectResult result = bridge.connectionOpened(connection);
        if (result == ModernClientBridge.ConnectResult.REDIRECT_LANDED) {
            bridge.debug(L10n.tr("fclient.switchDone"));
            controller.onRedirectLanded();
            return;
        }
        if (result == ModernClientBridge.ConnectResult.REDIRECT_MISSED) {
            controller.onRedirectNotLanded();
        }
        controller.shutdown();
        Credentials warm = warmupMatch(connection);
        if (warm != null
                && controller.adoptDirectConnection(warm, warmup.readyEvent(warm.dedupKey()))) {
            bridge.debug(L10n.tr("fclient.adopted"));
        }
    }

    /** 连接断开。 */
    public void onDisconnected(Connection connection) {
        ModernClientBridge.DisconnectResult result = bridge.connectionClosed(connection);
        if (result == ModernClientBridge.DisconnectResult.REDIRECT_ORIGIN) {
            bridge.debug(L10n.tr("fclient.disconnectBySwitch"));
            controller.onDisconnected();
            return;
        }
        if (result == ModernClientBridge.DisconnectResult.STALE) {
            bridge.debug(L10n.tr("fclient.staleDisconnect"));
            return;
        }
        if (controller.redirectInProgress()) {
            bridge.debug(L10n.tr("fclient.redirectCommitted"));
            controller.onDisconnected();
            return;
        }
        bridge.debug(L10n.tr("fclient.playerLeft"));
        controller.shutdown();
    }

    /** 新连接的目标是就绪预热隧道的回环端口时返回其凭证，否则 null。 */
    private Credentials warmupMatch(Connection connection) {
        SocketAddress remote = connection == null ? null : connection.getRemoteAddress();
        if (!(remote instanceof InetSocketAddress)) {
            return null;
        }
        InetSocketAddress addr = (InetSocketAddress) remote;
        if (addr.getAddress() == null || !addr.getAddress().isLoopbackAddress()) {
            return null;
        }
        return warmup.credentialsForPort(addr.getPort());
    }
}
