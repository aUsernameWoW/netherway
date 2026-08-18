package cn.ripplecraft.netherway.modern.client;

import cn.ripplecraft.netherway.core.ClientBridge;
import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.ServerCandidates;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * {@link ClientBridge} 的 modern（1.16.5+）实现，Forge 与 Fabric 共用。
 *
 * <p>只用 mojmap 下 1.16.5–1.20.1 稳定的客户端 API（{@code Minecraft.execute}、
 * {@code getCurrentServer}、{@code Connection.getRemoteAddress}），逐版分叉的
 * 两处（发起连接、聊天文本）经 {@link GameOps} 注入。连接/断开的身份跟踪用
 * {@code net.minecraft.network.Connection}（三版本、两 loader 同型），逻辑与
 * forge-1.7.10 的 ForgeClientBridge 逐条对应。
 *
 * <p>与 1.7.10 版的一处刻意简化：{@link #currentServerAddress} 用
 * {@link ServerCandidates#parse}（不做 SRV 解析），与路由表 resolve 的 key
 * 完全同源。1.7.10 用 SRV 感知的 ServerAddress 求 origin、却用 raw 解析求
 * 路由 key，两者对 SRV 服务器可能对不上；这里两侧统一，更自洽。
 */
public final class ModernClientBridge implements ClientBridge {

    private static final Logger LOG = LogManager.getLogger("netherway");
    /** 回环目标正常只需数 tick；这里留足慢机器启动网络栈的余量。 */
    private static final long REDIRECT_TIMEOUT_NANOS = 30L * 1000L * 1000L * 1000L;

    private final GameOps ops;
    private final ClientNetwork network;
    private final boolean verboseLog;

    /** 升级重定向的目标端口；0 表示当前没有进行中的重定向。 */
    private volatile int redirectPort;

    private Connection activeConnection;
    private Connection redirectOriginConnection;
    private long redirectDeadlineNanos;

    /** 发起切换时玩家所在服务器的地址；只在重定向生命周期内有效。 */
    private volatile ServerCandidates.Address switchOrigin;

    public ModernClientBridge(GameOps ops, ClientNetwork network, boolean verboseLog) {
        this.ops = ops;
        this.network = network;
        this.verboseLog = verboseLog;
    }

    @Override
    public void runOnGameThread(Runnable task) {
        // Minecraft.execute 在 1.16+ 就是干净的主线程派发口，不必再自建 tick 队列
        Minecraft.getInstance().execute(task);
    }

    enum ConnectResult {
        ORDINARY,
        REDIRECT_LANDED,
        REDIRECT_MISSED
    }

    enum DisconnectResult {
        ACTIVE,
        STALE,
        REDIRECT_ORIGIN
    }

    /** 新连接建立时由入口调用：更新活动连接并判断是否为本轮重定向目标。 */
    public synchronized ConnectResult connectionOpened(Connection connection) {
        int expected = redirectPort;
        if (expected == 0) {
            activeConnection = connection;
            switchOrigin = null;
            return ConnectResult.ORDINARY;
        }
        boolean landed = isRedirectTarget(connection, expected);
        SocketAddress remote = connection == null ? null : connection.getRemoteAddress();
        activeConnection = connection;
        clearRedirectTracking();
        if (landed) {
            debug(L10n.tr("fbridge.redirectLanded", remote));
            return ConnectResult.REDIRECT_LANDED;
        }
        switchOrigin = null;
        debug(L10n.tr("fbridge.redirectMissed", remote, expected));
        return ConnectResult.REDIRECT_MISSED;
    }

    /** 断开事件按连接身份归类。语义同 1.7.10 版。 */
    public synchronized DisconnectResult connectionClosed(Connection connection) {
        if (redirectPort != 0) {
            if (connection != null && (connection == redirectOriginConnection
                    || connection == activeConnection)) {
                if (activeConnection == connection) {
                    activeConnection = null;
                }
                return DisconnectResult.REDIRECT_ORIGIN;
            }
            return DisconnectResult.STALE;
        }
        if (connection != null && connection == activeConnection) {
            activeConnection = null;
            switchOrigin = null;
            return DisconnectResult.ACTIVE;
        }
        return DisconnectResult.STALE;
    }

    /** tick 上的有界兜底；返回 true 只发生一次，由入口完成 controller 收口。 */
    public synchronized boolean redirectTimedOut() {
        if (redirectPort == 0 || System.nanoTime() - redirectDeadlineNanos < 0L) {
            return false;
        }
        int expected = redirectPort;
        clearRedirectTracking();
        switchOrigin = null;
        debug(L10n.tr("fbridge.redirectTimeout", expected));
        return true;
    }

    private static boolean isRedirectTarget(Connection connection, int expectedPort) {
        SocketAddress remote = connection == null ? null : connection.getRemoteAddress();
        if (!(remote instanceof InetSocketAddress)) {
            return false;
        }
        InetSocketAddress addr = (InetSocketAddress) remote;
        return addr.getPort() == expectedPort
                && addr.getAddress() != null
                && addr.getAddress().isLoopbackAddress();
    }

    private void clearRedirectTracking() {
        redirectPort = 0;
        redirectOriginConnection = null;
        redirectDeadlineNanos = 0L;
    }

    @Override
    public void connectTo(String host, int port) {
        debug(L10n.tr("fbridge.connectTo", host, port));
        // 发起连接会退出当前世界并清掉 getCurrentServer，之后推导不出原服务器；
        // 而重定向落地后服务端会重发凭证需要它。必须赶在清掉之前存好。
        ServerCandidates.Address origin = currentServerAddress();
        synchronized (this) {
            redirectPort = port;
            redirectOriginConnection = activeConnection;
            redirectDeadlineNanos = System.nanoTime() + REDIRECT_TIMEOUT_NANOS;
            switchOrigin = origin;
        }
        try {
            ops.connect(host, port);
        } catch (RuntimeException e) {
            synchronized (this) {
                clearRedirectTracking();
                switchOrigin = null;
            }
            throw e;
        }
    }

    /**
     * 玩家当前正在连的服务器地址，取自 {@code Minecraft.getCurrentServer()}。
     *
     * <p><b>1.20.1 上连接一断即为 null</b>（数据搬进了连接对象），1.16.5/1.18.2
     * 是 clearLevel 清空——三版本都必须靠 {@link #switchOrigin} 兜底。仍显式挡掉
     * 回环：玩家可能从直连条目进服，那一条推导不出真实服务器，返回 null 更诚实。
     */
    @Override
    public ServerCandidates.Address currentServerAddress() {
        ServerData data = Minecraft.getInstance().getCurrentServer();
        if (data == null || data.ip == null || data.ip.isEmpty()) {
            return switchOrigin;
        }
        ServerCandidates.Address parsed = ServerCandidates.parse(data.ip);
        if (parsed == null || isLoopback(parsed.host)) {
            return switchOrigin;
        }
        return parsed;
    }

    private static boolean isLoopback(String host) {
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        return h.equals("localhost") || h.equals("::1") || h.startsWith("127.");
    }

    @Override
    public void notifyPlayer(String message) {
        ops.sendChat(L10n.tr("chat.prefix") + message);
    }

    @Override
    public void sendToServer(byte[] payload) {
        // 升级线程会调进来，经主线程派发再发；没有连接时安静丢弃
        runOnGameThread(new Runnable() {
            @Override
            public void run() {
                if (Minecraft.getInstance().getConnection() == null) {
                    debug(L10n.tr("fbridge.noConnection"));
                    return;
                }
                network.sendToServer(payload);
            }
        });
    }

    @Override
    public Path cacheDirectory() {
        return new File(Minecraft.getInstance().gameDirectory, "netherway").toPath();
    }

    @Override
    public void info(String message) {
        LOG.info(message);
    }

    @Override
    public void warn(String message, Throwable error) {
        LOG.warn(message, error);
    }

    @Override
    public void debug(String message) {
        if (verboseLog) {
            LOG.info(message);
        } else {
            LOG.debug(message);
        }
    }
}
