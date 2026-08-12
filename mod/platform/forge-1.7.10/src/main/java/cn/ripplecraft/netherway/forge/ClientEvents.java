package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.UpgradeController;
import cn.ripplecraft.netherway.core.WarmupController;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.minecraft.network.NetworkManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端事件接线：凭证包、tick 泵、连接建立/断开。
 *
 * <p>断开事件要区分两种情况，这是 core 留给平台层的活：
 * <ul>
 * <li>升级引发的断开——我们自己调 {@code connectTo} 造成的，此刻 agent
 *     正要承载新连接，绝不能停它；</li>
 * <li>真正的退出/掉线——必须停掉 agent，否则孤儿进程占着端口。</li>
 * </ul>
 * 靠 {@link ForgeClientBridge} 记录的连接 manager 身份区分：切换开始时记住
 * origin manager，后续每个 connected/disconnected 都按事件自己的 manager
 * 归属，因而不依赖 Forge 对新旧事件的到达顺序。
 */
public final class ClientEvents {

    private static final Logger LOG = LogManager.getLogger(Netherway.MODID);

    private final UpgradeController controller;
    private final WarmupController warmup;
    private final ForgeClientBridge bridge;

    public ClientEvents(UpgradeController controller, WarmupController warmup,
                        ForgeClientBridge bridge) {
        this.controller = controller;
        this.warmup = warmup;
        this.bridge = bridge;
    }

    /** 服务端在我们的频道上发来了凭证。事件在 netty 线程触发。 */
    @SubscribeEvent
    public void onCredentials(FMLNetworkEvent.ClientCustomPacketEvent event) {
        ByteBuf buf = event.packet.payload();
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        bridge.debug("收到服务端凭证包，" + data.length + " 字节");
        try {
            Credentials cred = Credentials.decode(data);
            controller.onCredentials(cred);
        } catch (IOException e) {
            // 损坏或过旧的凭证只影响升级，不影响玩家当前的连接
            LOG.warn("凭证解码失败，忽略本次下发", e);
        }
    }

    /** 把后台线程排进来的任务泵到游戏主线程。 */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            bridge.drainTasks();
            if (bridge.redirectTimedOut()) {
                // 本机目标没有产生可供事件总线观察的成功/失败事件，主动收口，
                // 避免 UPGRADED 和 agent 永久残留。
                controller.onRedirectNotLanded();
                controller.shutdown();
            }
        }
    }

    @SubscribeEvent
    public void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        ForgeClientBridge.ConnectResult result = bridge.connectionOpened(event.manager);
        if (result == ForgeClientBridge.ConnectResult.REDIRECT_LANDED) {
            // 我们发起的切换成功落地，隧道由 agent 继续承载，什么都不用做
            bridge.debug("直连切换完成，agent 继续承载新连接");
            controller.onRedirectLanded();
            return;
        }
        if (result == ForgeClientBridge.ConnectResult.REDIRECT_MISSED) {
            controller.onRedirectNotLanded();
        }
        // 与升级无关的新连接（换服、重进）：上一局若有残留的 agent，清掉。
        // 必须先复位再采认——采认要求状态机在 IDLE。
        controller.shutdown();
        // 玩家经服务器列表的直连条目进服：连接目标正是预热隧道的回环端口。
        // 采认后，服务端照常下发的凭证会命中「已在直连」分支并回执成功。
        Credentials warm = warmupMatch(event.manager);
        if (warm != null
                && controller.adoptDirectConnection(warm, warmup.readyEvent(warm.dedupKey()))) {
            bridge.debug("玩家经直连条目进服，已采认预热隧道");
        }
    }

    /** 新连接的目标是就绪预热隧道的回环端口时返回其凭证，否则 null。 */
    private Credentials warmupMatch(NetworkManager manager) {
        SocketAddress remote = manager == null ? null : manager.getSocketAddress();
        if (!(remote instanceof InetSocketAddress)) {
            return null; // 单人游戏的本地通道等
        }
        InetSocketAddress addr = (InetSocketAddress) remote;
        if (addr.getAddress() == null || !addr.getAddress().isLoopbackAddress()) {
            return null;
        }
        return warmup.credentialsForPort(addr.getPort());
    }

    @SubscribeEvent
    public void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ForgeClientBridge.DisconnectResult result = bridge.connectionClosed(event.manager);
        if (result == ForgeClientBridge.DisconnectResult.REDIRECT_ORIGIN) {
            // 升级引发的旧连接断开：马上要连的就是这条隧道，不能停 agent
            bridge.debug("断开由直连切换引发，保留 agent 等待新连接落地");
            controller.onDisconnected();
            return;
        }
        if (result == ForgeClientBridge.DisconnectResult.STALE) {
            // 可能是新连接先落地后的旧连接迟到，也可能是尚未 connected 的
            // 目标失败；后者无法可靠辨认，交给 bridge 的有界 timeout 收口。
            bridge.debug("忽略非当前连接的迟到断开事件");
            return;
        }
        if (controller.redirectInProgress()) {
            // controller 已提交重定向，但 bridge.connectTo 尚未把 manager tracker
            // 立起来时，旧连接也可能先报断开。靠 controller 代际补上这个窄窗。
            bridge.debug("直连切换已提交，保留 agent 等待 bridge 开始目标连接");
            controller.onDisconnected();
            return;
        }
        // 真退出：无论升级到哪一步都彻底停掉并复位。
        // UPGRADED 下 onDisconnected() 会误以为断开是升级造成的而放过 agent，
        // 所以这里必须用 shutdown()。
        bridge.debug("玩家离开服务器，停止 agent 并复位");
        controller.shutdown();
    }
}
