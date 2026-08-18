package cn.ripplecraft.netherway.bukkit;

import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.modern.CredentialService;
import cn.ripplecraft.netherway.modern.ModConfig;
import cn.ripplecraft.netherway.modern.NetherwayModern;
import cn.ripplecraft.netherway.modern.ServerRuntime;
import cn.ripplecraft.netherway.modern.TelemetryWiring;
import cn.ripplecraft.netherway.modern.UpgradeReportService;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Bukkit/Spigot/Paper entry for the server half of Netherway. Players still
 * run the client mod; this plugin replaces the server-side mod, so Paper-family
 * servers get P2P direct connections without switching to a modded server.
 *
 * <p>It reuses the Minecraft-free server classes from {@code modern/shared}
 * unchanged (same {@code netherway.cfg}, same behaviour as the Forge/Fabric
 * server half). Only three things are Bukkit-specific:
 * <ul>
 * <li>credential delivery and upgrade-report receipt ride the Plugin
 *     Messaging API on the {@code netherway:main} channel;</li>
 * <li>the connection sniffer is attached by {@link PipelineInjector}
 *     (reflection instead of a Mixin);</li>
 * <li>the PROXY-protocol address write-back is {@link NmsAddressRewriter}
 *     (reflection by field type instead of a Mixin accessor).</li>
 * </ul>
 *
 * <p>Credentials are sent when the client registers our channel
 * ({@link PlayerRegisterChannelEvent}) rather than on join: CraftBukkit
 * silently drops plugin messages to channels the client has not registered,
 * and registration may arrive after the join event. Clients that never
 * register (or very old mods) still self-serve through preauth on the MC
 * port, so in-game delivery is a fast path, not the only path.
 */
public final class NetherwayPlugin extends JavaPlugin implements Listener, PluginMessageListener {

    /** Namespaced 1.13+ form of the custom payload channel. */
    private static final String CHANNEL =
            NetherwayModern.CHANNEL_NAMESPACE + ":" + NetherwayModern.CHANNEL_PATH;

    private ServerRuntime runtime;
    private CredentialService credentials;
    private UpgradeReportService reports;
    private PipelineInjector injector;

    /**
     * Players already given credentials in this login session. Guards against
     * duplicate channel-register payloads; cleared on quit so every new login
     * gets a fresh (re-signed) credential, same as the mod servers.
     */
    private final Set<UUID> delivered = new HashSet<UUID>();

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        ModConfig config = ModConfig.loadSafely(
                new File(getDataFolder(), "netherway.cfg").toPath());
        TelemetryWiring wiring = new TelemetryWiring(
                getDescription().getVersion(), serverMcVersion());
        runtime = new ServerRuntime(config, wiring, new NmsAddressRewriter());
        credentials = new CredentialService(config);
        reports = new UpgradeReportService(config);

        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);

        // Assembles the sniffer context and starts the built-in serve agent
        // (both no-ops unless server.enabled).
        runtime.onServerStarted(getServer().getPort());

        if (config.serverEnabled()) {
            injector = new PipelineInjector();
            // The listen channel may not be bound yet while plugins are still
            // enabling; retry once the server has fully started (first tick).
            if (!install(false)) {
                getServer().getScheduler().runTask(this, new Runnable() {
                    @Override
                    public void run() {
                        install(true);
                    }
                });
            }
        }
    }

    private boolean install(boolean lastAttempt) {
        int hooked = injector.install();
        if (hooked > 0) {
            getLogger().info(L10n.tr("bukkit.injected", hooked));
            return true;
        }
        if (lastAttempt) {
            getLogger().warning(L10n.tr("bukkit.injectFailed", injector.lastError()));
        }
        return false;
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.onServerStopping();
        }
        if (injector != null) {
            injector.uninstall();
            injector = null;
        }
        delivered.clear();
    }

    // ---------- credential delivery (server -> client) ----------

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (CHANNEL.equals(event.getChannel())) {
            deliver(event.getPlayer());
        }
    }

    /** Covers clients whose channel registration arrived before the join event. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (event.getPlayer().getListeningPluginChannels().contains(CHANNEL)) {
            deliver(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        delivered.remove(event.getPlayer().getUniqueId());
    }

    private void deliver(Player player) {
        if (!delivered.add(player.getUniqueId())) {
            return;
        }
        byte[] payload = credentials.credentialsFor(
                player.getUniqueId().toString(), player.getName());
        if (payload != null) {
            player.sendPluginMessage(this, CHANNEL, payload);
        }
    }

    // ---------- upgrade reports (client -> server) ----------

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || message == null
                || message.length == 0 || message.length > UpgradeReportService.MAX_PAYLOAD_BYTES) {
            return;
        }
        reports.onReport(player.getName(), message);
    }

    // ---------- helpers ----------

    /** Minecraft version for telemetry, e.g. "1.20.1" out of "1.20.1-R0.1-SNAPSHOT". */
    private String serverMcVersion() {
        String v = getServer().getBukkitVersion();
        int dash = v.indexOf('-');
        return dash > 0 ? v.substring(0, dash) : v;
    }
}
