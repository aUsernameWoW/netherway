package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.ClientBridge;
import cn.ripplecraft.netherway.core.L10n;
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
            sender.addChatMessage(new ChatComponentText(L10n.tr("telemetry.status",
                    telemetry.enabled() ? (telemetry.enhanced() ? "enhanced" : "basic")
                            : "disabled",
                    telemetry.pendingCount(),
                    telemetry.droppedCount(),
                    telemetry.transportConfigured()
                            ? L10n.tr("telemetry.transportConfigured")
                            : L10n.tr("telemetry.transportMissing"))));
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
            LOG.info(L10n.tr("telemetry.previewLog", payload));
            sender.addChatMessage(new ChatComponentText(
                    L10n.tr("telemetry.previewWritten", file.toAbsolutePath())));
        } catch (IOException e) {
            LOG.warn(L10n.tr("telemetry.previewFailed"), e);
            sender.addChatMessage(new ChatComponentText(
                    L10n.tr("telemetry.previewFailedChat", e.getMessage())));
        }
    }
}
