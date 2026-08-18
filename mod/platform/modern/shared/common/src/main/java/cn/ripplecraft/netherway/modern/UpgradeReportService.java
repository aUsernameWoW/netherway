package cn.ripplecraft.netherway.modern;

import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.UpgradeReport;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 接收客户端回传的升级结果并记进日志，与 MC/loader 无关：入口把玩家名与
 * 包体交进来。回执是纯诊断信息，解码失败/被限流都只是丢弃，绝不影响连接。
 *
 * <p>输入来自网络上的任意客户端，按不可信数据对待：包体有大小上限、文本经
 * {@link UpgradeReport} 清洗、每个玩家有回执频率上限。
 */
public final class UpgradeReportService {

    private static final Logger LOG = LogManager.getLogger(NetherwayModern.MODID);

    /** 回执包体上限。正常回执几十字节，超出的必是恶意构造。 */
    public static final int MAX_PAYLOAD_BYTES = 1024;
    /** 同一玩家两次回执的最小间隔。正常流程一次登录至多一条。 */
    private static final long MIN_INTERVAL_MS = 5_000L;
    /** 限流表的容量上限，防止恶意换名撑爆内存。 */
    private static final int MAX_TRACKED_PLAYERS = 512;

    private final ModConfig config;
    private final Map<String, Long> lastReportAt = new HashMap<String, Long>();

    public UpgradeReportService(ModConfig config) {
        this.config = config;
    }

    /** 客户端在频道上发来了包。入口已提取玩家名与包体，并保证 size 合法。 */
    public void onReport(String player, byte[] data) {
        if (!config.serverEnabled()) {
            return;
        }
        if (data == null || data.length <= 0 || data.length > MAX_PAYLOAD_BYTES) {
            return;
        }
        if (throttled(player)) {
            return;
        }
        UpgradeReport report;
        try {
            report = UpgradeReport.decode(data);
        } catch (IOException e) {
            LOG.debug(L10n.tr("fserver.reportUndecodable", player));
            return;
        }
        if (report.isUpgraded()) {
            LOG.info(L10n.tr("fserver.reportUpgraded",
                    player, report.room(), report.rttMs(), report.elapsedMs()));
        } else {
            // 多个玩家同时失败通常意味着宿主机的 serve 进程或 frps 出了问题
            LOG.warn(L10n.tr("fserver.reportGaveUp",
                    player, report.room(), report.reason()));
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
