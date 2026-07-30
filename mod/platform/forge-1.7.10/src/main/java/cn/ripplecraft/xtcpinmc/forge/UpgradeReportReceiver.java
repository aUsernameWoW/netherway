package cn.ripplecraft.xtcpinmc.forge;

import cn.ripplecraft.xtcpinmc.core.UpgradeReport;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.NetHandlerPlayServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 服务端半边：接收客户端回传的升级结果并记进日志。
 *
 * <p>打洞结果只有客户端知道，没有这份回执，服主排查「为什么大家都没直连」
 * 只能挨个找玩家要客户端日志。回执是纯诊断信息：解码失败、被限流都只是
 * 丢弃，绝不影响玩家连接。
 *
 * <p>输入来自网络上的任意客户端，按不可信数据对待：包体有大小上限、
 * 文本经 {@link UpgradeReport} 清洗、每个玩家有回执频率上限，
 * 防止恶意客户端刷爆服务端日志。
 */
public final class UpgradeReportReceiver {

    private static final Logger LOG = LogManager.getLogger(XtcpInMc.MODID);

    /** 回执包体上限。正常回执几十字节，超出的必是恶意构造。 */
    private static final int MAX_PAYLOAD_BYTES = 1024;
    /** 同一玩家两次回执的最小间隔。正常流程一次登录至多一条。 */
    private static final long MIN_INTERVAL_MS = 5_000L;
    /** 限流表的容量上限，防止恶意换名撑爆内存。 */
    private static final int MAX_TRACKED_PLAYERS = 512;

    private final ModConfig config;
    private final Map<String, Long> lastReportAt = new HashMap<String, Long>();

    public UpgradeReportReceiver(ModConfig config) {
        this.config = config;
    }

    /** 客户端在我们的频道上发来了包。事件在 netty 线程触发。 */
    @SubscribeEvent
    public void onReport(FMLNetworkEvent.ServerCustomPacketEvent event) {
        if (!config.serverEnabled()) {
            return;
        }
        if (!(event.handler instanceof NetHandlerPlayServer)) {
            return;
        }
        String player = ((NetHandlerPlayServer) event.handler)
                .playerEntity.getCommandSenderName();

        ByteBuf buf = event.packet.payload();
        int size = buf.readableBytes();
        if (size <= 0 || size > MAX_PAYLOAD_BYTES) {
            return;
        }
        if (throttled(player)) {
            return;
        }
        byte[] data = new byte[size];
        buf.readBytes(data);

        UpgradeReport report;
        try {
            report = UpgradeReport.decode(data);
        } catch (IOException e) {
            // 损坏或过旧的回执只是少一条诊断日志，无需惊动服主
            LOG.debug("来自 {} 的结果回执无法解码，已忽略", player);
            return;
        }

        if (report.isUpgraded()) {
            LOG.info("{} 已切换为直连（房间 {}，延迟 {}ms，建链 {}ms）",
                    player, report.room(), report.rttMs(), report.elapsedMs());
        } else {
            // 失败原因用 warn：多个玩家同时失败通常意味着宿主机的
            // serve 进程或 frps 出了问题，值得服主注意
            LOG.warn("{} 直连失败（房间 {}）：{}",
                    player, report.room(), report.reason());
        }
    }

    private synchronized boolean throttled(String player) {
        long now = System.currentTimeMillis();
        Long last = lastReportAt.get(player);
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return true;
        }
        if (lastReportAt.size() >= MAX_TRACKED_PLAYERS && !lastReportAt.containsKey(player)) {
            lastReportAt.clear();
        }
        lastReportAt.put(player, now);
        return false;
    }
}
