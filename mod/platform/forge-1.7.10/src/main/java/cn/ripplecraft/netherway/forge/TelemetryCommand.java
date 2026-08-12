package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.ClientBridge;
import cn.ripplecraft.netherway.core.telemetry.TelemetryCollector;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Local-only inspection command; it never sends or mutates telemetry. */
final class TelemetryCommand extends CommandBase {

    private static final Logger LOG = LogManager.getLogger(Netherway.MODID);
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final TelemetryCollector telemetry;
    private final ClientBridge bridge;

    TelemetryCommand(TelemetryCollector telemetry, ClientBridge bridge) {
        this.telemetry = telemetry;
        this.bridge = bridge;
    }

    @Override
    public String getCommandName() {
        return "netherwaytelemetry";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/netherwaytelemetry <status|preview>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length != 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        if ("status".equalsIgnoreCase(args[0])) {
            sender.addChatMessage(new ChatComponentText("Netherway 遥测: "
                    + (telemetry.enabled() ? (telemetry.enhanced() ? "enhanced" : "basic")
                    : "disabled")
                    + "，待处理摘要 " + telemetry.pendingCount()
                    + "，已丢弃 " + telemetry.droppedCount()
                    + "，" + (telemetry.transportConfigured()
                    ? "传输已配置" : "未配置上传端点（仅本地预览）")));
            return;
        }
        if ("preview".equalsIgnoreCase(args[0])) {
            writePreview(sender);
            return;
        }
        throw new WrongUsageException(getCommandUsage(sender));
    }

    private void writePreview(ICommandSender sender) {
        String payload = telemetry.previewPayload();
        Path file = bridge.cacheDirectory().resolve("telemetry-preview.json");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, payload.getBytes(UTF8));
            LOG.info("下一批遥测 payload 预览: {}", payload);
            sender.addChatMessage(new ChatComponentText("遥测预览已写入 "
                    + file.toAbsolutePath() + "，并输出到游戏日志"));
        } catch (IOException e) {
            LOG.warn("写入遥测预览失败", e);
            sender.addChatMessage(new ChatComponentText("写入遥测预览失败: " + e.getMessage()));
        }
    }
}
