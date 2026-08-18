package cn.ripplecraft.netherway.modern.client;

import cn.ripplecraft.netherway.core.AgentEvent;
import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.ServerCandidates;
import cn.ripplecraft.netherway.core.WarmupController;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 把持久的 Minecraft 入口与本次进程里的预热端口分开。
 *
 * <p>{@code servers.dat} 永远保留玩家填写的真实入口。覆盖模式下，只有 READY
 * 的隧道才会进入这张内存路由表，服务器列表点击时再把那一次连接解析到回环；
 * 非覆盖模式则沿用独立直连条目。游戏崩溃或移除 mod 都不会留下失效回环地址。
 */
public final class WarmupEntryRouter implements WarmupController.Listener {

    static final class Route {
        final Credentials credentials;
        final int port;

        Route(Credentials credentials, int port) {
            this.credentials = credentials;
            this.port = port;
        }

        String localAddress() {
            return "127.0.0.1:" + port;
        }
    }

    private final boolean replaceEntries;
    private final WarmupController.Listener directEntries;
    private final cn.ripplecraft.netherway.core.ClientBridge bridge;
    private final ConcurrentMap<String, Route> routes =
            new ConcurrentHashMap<String, Route>();

    WarmupEntryRouter(boolean replaceEntries, WarmupController.Listener directEntries,
                      cn.ripplecraft.netherway.core.ClientBridge bridge) {
        this.replaceEntries = replaceEntries;
        this.directEntries = directEntries;
        this.bridge = bridge;
    }

    boolean replacesEntries() {
        return replaceEntries;
    }

    @Override
    public void onTunnelStarting(Credentials cred, int port) {
        if (!replaceEntries && directEntries != null) {
            directEntries.onTunnelStarting(cred, port);
        }
    }

    @Override
    public void onTunnelReady(Credentials cred, AgentEvent event) {
        if (!replaceEntries) {
            if (directEntries != null) {
                directEntries.onTunnelReady(cred, event);
            }
            return;
        }
        String key = originKey(cred);
        if (key == null || event == null || event.port() <= 0) {
            return;
        }
        Route route = new Route(cred, event.port());
        routes.put(key, route);
        if (bridge != null) {
            bridge.info(L10n.tr("router.overridden", key, route.localAddress()));
        }
    }

    @Override
    public void onTunnelClosed(Credentials cred, int port) {
        if (!replaceEntries) {
            if (directEntries != null) {
                directEntries.onTunnelClosed(cred, port);
            }
            return;
        }
        String key = originKey(cred);
        if (key == null) {
            return;
        }
        Route current = routes.get(key);
        if (current != null && current.port == port
                && current.credentials.dedupKey().equals(cred.dedupKey())
                && routes.remove(key, current) && bridge != null) {
            bridge.info(L10n.tr("router.restored", key));
        }
    }

    /** 返回点击该持久入口时应使用的 READY 路由；不做 DNS/SRV 查询。 */
    Route resolve(String serverAddress) {
        if (!replaceEntries) {
            return null;
        }
        ServerCandidates.Address address = ServerCandidates.parse(serverAddress);
        return address == null ? null : routes.get(key(address.host, address.port));
    }

    private static String originKey(Credentials cred) {
        return cred == null || !cred.hasOrigin()
                ? null : key(cred.originHost(), cred.originPort());
    }

    private static String key(String host, int port) {
        return host.toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }
}
