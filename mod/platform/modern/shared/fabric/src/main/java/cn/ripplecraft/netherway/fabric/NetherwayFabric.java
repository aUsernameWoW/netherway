package cn.ripplecraft.netherway.fabric;

import cn.ripplecraft.netherway.modern.CredentialService;
import cn.ripplecraft.netherway.modern.ModConfig;
import cn.ripplecraft.netherway.modern.NetherwayModern;
import cn.ripplecraft.netherway.modern.ServerRuntime;
import cn.ripplecraft.netherway.modern.TelemetryWiring;
import cn.ripplecraft.netherway.modern.UpgradeReportService;
import cn.ripplecraft.netherway.modern.VersionInfo;
import cn.ripplecraft.netherway.modern.VersionServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Fabric 通用入口（服务端半边 + 公共装配）。Fabric API 在 1.16.5–1.20.1
 * 形态稳定，本类三版本共用（各自编译一次）；版本相关的端口/目录取用交给
 * 各版本自带的 {@link VersionServer}。
 */
public final class NetherwayFabric implements ModInitializer {

    private static final Logger LOG = LogManager.getLogger(NetherwayModern.MODID);
    static final ResourceLocation CHANNEL = new ResourceLocation(
            NetherwayModern.CHANNEL_NAMESPACE, NetherwayModern.CHANNEL_PATH);

    @Override
    public void onInitialize() {
        ModConfig config = ModConfig.loadSafely(
                FabricLoader.getInstance().getConfigDir().resolve("netherway.cfg"));
        TelemetryWiring wiring = new TelemetryWiring(modVersion(), VersionInfo.MC_VERSION);

        // 服务端遥测在 ServerRuntime 内部按 DEDICATED_SERVER 装配；这里给出
        // 服务端半边的整体装配。客户端半边在 NetherwayFabricClient。
        final ServerRuntime server = new ServerRuntime(config, wiring,
                new cn.ripplecraft.netherway.modern.ConnectionAddressRewriter());
        final CredentialService credentials = new CredentialService(config);
        final UpgradeReportService reports = new UpgradeReportService(config);

        // 玩家登录后下发凭证
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayer player = handler.player;
            byte[] payload = credentials.credentialsFor(
                    player.getUUID().toString(), player.getGameProfile().getName());
            if (payload != null) {
                FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                buf.writeBytes(payload);
                ServerPlayNetworking.send(player, CHANNEL, buf);
            }
        });

        // 客户端回传升级结果
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL,
                (srv, player, handler, buf, responseSender) -> {
                    int size = buf.readableBytes();
                    if (size <= 0 || size > UpgradeReportService.MAX_PAYLOAD_BYTES) {
                        return;
                    }
                    byte[] data = new byte[size];
                    buf.readBytes(data);
                    String name = player.getGameProfile().getName();
                    srv.execute(() -> reports.onReport(name, data));
                });

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED
                .register(srv -> server.onServerStarted(VersionServer.mcPort(srv)));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING
                .register(srv -> server.onServerStopping());

        LOG.info("Netherway (Fabric {}) initialized", VersionInfo.MC_VERSION);
    }

    static String modVersion() {
        return FabricLoader.getInstance().getModContainer(NetherwayModern.MODID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");
    }
}
