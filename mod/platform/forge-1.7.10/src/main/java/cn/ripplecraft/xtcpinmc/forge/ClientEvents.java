package cn.ripplecraft.xtcpinmc.forge;

import cn.ripplecraft.xtcpinmc.core.Credentials;
import cn.ripplecraft.xtcpinmc.core.UpgradeController;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
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
 * 靠 {@link ForgeClientBridge} 的「重定向进行中」标志区分：标志在
 * {@code connectTo} 里先立起来，新连接落地（或被识别为无关连接）时清掉。
 */
public final class ClientEvents {

    private static final Logger LOG = LogManager.getLogger(XtcpInMc.MODID);

    private final UpgradeController controller;
    private final ForgeClientBridge bridge;

    public ClientEvents(UpgradeController controller, ForgeClientBridge bridge) {
        this.controller = controller;
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
            controller.onCredentials(Credentials.decode(data));
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
        }
    }

    @SubscribeEvent
    public void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        if (bridge.redirectLanded(event.manager)) {
            // 我们发起的切换成功落地，隧道由 agent 继续承载，什么都不用做
            bridge.debug("直连切换完成，agent 继续承载新连接");
            return;
        }
        // 与升级无关的新连接（换服、重进）：上一局若有残留的 agent，清掉
        controller.shutdown();
    }

    @SubscribeEvent
    public void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        if (bridge.redirectInFlight()) {
            // 升级引发的断开：马上要连的就是这条隧道，不能停 agent
            bridge.debug("断开由直连切换引发，保留 agent 等待新连接落地");
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
