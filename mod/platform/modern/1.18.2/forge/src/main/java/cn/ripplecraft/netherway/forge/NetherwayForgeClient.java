package cn.ripplecraft.netherway.forge;

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
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.event.EventNetworkChannel;

/**
 * Forge 1.18.2 客户端半边。经 {@link net.minecraftforge.fml.DistExecutor} 只在
 * 物理客户端加载（专用服务器类路径上没有 net.minecraft.client）。装配共享的
 * {@link ClientRuntime}，把 Forge 客户端事件转成它的回调。
 */
public final class NetherwayForgeClient {

    private static ClientRuntime runtime;

    private NetherwayForgeClient() {
    }

    public static void init(ModConfig config, EventNetworkChannel channel) {
        if ("auto".equalsIgnoreCase(config.language())) {
            String gameLang = VersionClient.gameLanguage();
            if (gameLang != null && !gameLang.isEmpty()) {
                L10n.use(gameLang);
            }
        }
        TelemetryWiring wiring = new TelemetryWiring(
                NetherwayForge.modVersion(), VersionInfo.MC_VERSION);
        TelemetryCollector telemetry = wiring.collector(config, TelemetryEnvironment.Role.CLIENT);
        TelemetryFlusher.start(telemetry, 60L);
        if (!config.clientEnabled()) {
            return;
        }

        ClientNetwork network = payload -> {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
            Minecraft.getInstance().getConnection().send(
                    new ServerboundCustomPayloadPacket(NetherwayForge.CHANNEL, buf));
        };
        SessionIdentity session = VersionClient.session();
        runtime = ClientRuntime.create(config, telemetry, VersionClient.ops(), network,
                session, VersionClient.directFactory());

        // 服务端下发的凭证（客户端方向）
        channel.addListener(event -> {
            net.minecraftforge.network.NetworkEvent.Context ctx = event.getSource().get();
            if (ctx.getSender() != null) {
                return; // 只处理客户端方向
            }
            FriendlyByteBuf buf = event.getPayload();
            if (buf == null) {
                return;
            }
            int size = buf.readableBytes();
            byte[] data = new byte[size];
            buf.readBytes(data);
            ctx.enqueueWork(() -> runtime.onCredentials(data));
            ctx.setPacketHandled(true);
        });

        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new NetherwayForgeClient());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            runtime.clientTick();
        }
    }

    @SubscribeEvent
    public void onLoggedIn(ClientPlayerNetworkEvent.LoggedIn event) {
        // 1.18.2：LoggedIn/LoggedOut（1.19.3 起才改名为 LoggingIn/LoggingOut）。
        // 取连接用 getConnection()；若某 40.x 版本编译报错，改为 getNetworkManager()。
        if (event.getConnection() != null) {
            runtime.onConnected(event.getConnection());
        }
    }

    @SubscribeEvent
    public void onLoggedOut(ClientPlayerNetworkEvent.LoggedOut event) {
        // LoggedOut 的字段可空，且新建集成服务器时也会触发——都交给
        // ClientRuntime 里按连接身份归类的逻辑消化（null 连接视作与切换无关）。
        if (event.getConnection() != null) {
            runtime.onDisconnected(event.getConnection());
        }
    }
}
