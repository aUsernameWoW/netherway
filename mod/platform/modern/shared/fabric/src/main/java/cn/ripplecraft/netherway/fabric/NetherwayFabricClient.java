package cn.ripplecraft.netherway.fabric;

import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.SessionIdentity;
import cn.ripplecraft.netherway.core.telemetry.TelemetryCollector;
import cn.ripplecraft.netherway.core.telemetry.TelemetryEnvironment;
import cn.ripplecraft.netherway.core.telemetry.TelemetryFlusher;
import cn.ripplecraft.netherway.modern.ModConfig;
import cn.ripplecraft.netherway.modern.NetherwayModern;
import cn.ripplecraft.netherway.modern.TelemetryWiring;
import cn.ripplecraft.netherway.modern.VersionInfo;
import cn.ripplecraft.netherway.modern.client.ClientNetwork;
import cn.ripplecraft.netherway.modern.client.ClientRuntime;
import cn.ripplecraft.netherway.modern.client.VersionClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Fabric 客户端入口。装配共享的 {@link ClientRuntime}，把 Fabric 的网络/
 * tick/连接事件转成它的回调。三版本共用（各自编译一次），版本相关的连接/
 * 会话取用交给各版本自带的 {@link VersionClient}。
 */
public final class NetherwayFabricClient implements ClientModInitializer {

    @Override
    public void onInitialize() {
        ModConfig config = ModConfig.loadSafely(
                net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getConfigDir().resolve("netherway.cfg"));
        // cfg 的 auto 在客户端精化为跟随游戏语言
        if ("auto".equalsIgnoreCase(config.language())) {
            String gameLang = VersionClient.gameLanguage();
            if (gameLang != null && !gameLang.isEmpty()) {
                L10n.use(gameLang);
            }
        }
        TelemetryWiring wiring = new TelemetryWiring(
                NetherwayFabric.modVersion(), VersionInfo.MC_VERSION);
        TelemetryCollector telemetry = wiring.collector(config, TelemetryEnvironment.Role.CLIENT);
        TelemetryFlusher.start(telemetry, 60L);
        if (!config.clientEnabled()) {
            return;
        }

        ClientNetwork network = payload -> {
            FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeBytes(payload);
            ClientPlayNetworking.send(NetherwayFabric.CHANNEL, buf);
        };
        SessionIdentity session = VersionClient.session();
        final ClientRuntime runtime = ClientRuntime.create(config, telemetry,
                VersionClient.ops(), network, session, VersionClient.directFactory());

        // 服务端下发的凭证
        ClientPlayNetworking.registerGlobalReceiver(NetherwayFabric.CHANNEL,
                (client, handler, buf, responseSender) -> {
                    int size = buf.readableBytes();
                    byte[] data = new byte[size];
                    buf.readBytes(data);
                    runtime.onCredentials(data);
                });

        ClientTickEvents.START_CLIENT_TICK.register(client -> runtime.clientTick());
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> runtime.onConnected(handler.getConnection()));
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> runtime.onDisconnected(handler.getConnection()));
    }
}
