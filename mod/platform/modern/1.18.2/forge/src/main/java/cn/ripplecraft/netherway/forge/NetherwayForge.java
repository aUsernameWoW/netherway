package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.modern.CredentialService;
import cn.ripplecraft.netherway.modern.ModConfig;
import cn.ripplecraft.netherway.modern.NetherwayModern;
import cn.ripplecraft.netherway.modern.ServerRuntime;
import cn.ripplecraft.netherway.modern.TelemetryWiring;
import cn.ripplecraft.netherway.modern.UpgradeReportService;
import cn.ripplecraft.netherway.modern.VersionInfo;
import cn.ripplecraft.netherway.modern.VersionServer;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forge 1.18.2 入口（服务端半边 + 公共装配）。客户端半边在
 * {@code NetherwayForgeClient}，经 {@link DistExecutor} 只在物理客户端加载。
 *
 * <p>与 Fabric 入口对称：同一份 {@link ServerRuntime}/{@link CredentialService}/
 * {@link UpgradeReportService} 共享逻辑，这里只做 Forge 事件与频道的接线。
 * 频道声明为 optional（放行 ABSENT/ACCEPTVANILLA）并注册 DisplayTest，
 * 保证没装 mod 的客户端照常进服——本 mod 是纯增强，绝不构成准入门槛。
 */
@Mod(NetherwayModern.MODID)
public final class NetherwayForge {

    private static final Logger LOG = LogManager.getLogger(NetherwayModern.MODID);
    public static final ResourceLocation CHANNEL = new ResourceLocation(
            NetherwayModern.CHANNEL_NAMESPACE, NetherwayModern.CHANNEL_PATH);
    private static final String PROTOCOL = "1";

    private final ModConfig config;
    private final ServerRuntime server;
    private final CredentialService credentials;
    private final UpgradeReportService reports;
    private final EventNetworkChannel channel;

    public NetherwayForge() {
        this.config = ModConfig.loadSafely(
                FMLPaths.CONFIGDIR.get().resolve("netherway.cfg"));
        TelemetryWiring wiring = new TelemetryWiring(modVersion(), VersionInfo.MC_VERSION);
        this.server = new ServerRuntime(config, wiring,
                new cn.ripplecraft.netherway.modern.ConnectionAddressRewriter());
        this.credentials = new CredentialService(config);
        this.reports = new UpgradeReportService(config);

        // optional 频道：放行 ABSENT/ACCEPTVANILLA，未装 mod 的客户端不被握手拒绝
        this.channel = NetworkRegistry.ChannelBuilder
                .named(CHANNEL)
                .networkProtocolVersion(() -> PROTOCOL)
                .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
                .serverAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
                .eventNetworkChannel();
        // 服务端收客户端回传的升级结果
        channel.addListener(event -> {
            net.minecraftforge.network.NetworkEvent.Context ctx = event.getSource().get();
            if (ctx.getSender() == null) {
                return; // 只处理服务端方向
            }
            FriendlyByteBuf buf = event.getPayload();
            if (buf == null) {
                return;
            }
            int size = buf.readableBytes();
            if (size <= 0 || size > UpgradeReportService.MAX_PAYLOAD_BYTES) {
                ctx.setPacketHandled(true);
                return;
            }
            byte[] data = new byte[size];
            buf.readBytes(data);
            String name = ctx.getSender().getGameProfile().getName();
            ctx.enqueueWork(() -> reports.onReport(name, data));
            ctx.setPacketHandled(true);
        });

        // DisplayTest：服务器不因缺少本 mod 而在客户端列表被标红
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(
                        () -> NetworkConstants.IGNORESERVERONLY,
                        (remote, isServer) -> true));

        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        DistExecutor.safeRunWhenOn(Dist.CLIENT,
                () -> () -> NetherwayForgeClient.init(config, channel));
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        ServerPlayer player = (ServerPlayer) event.getEntity();
        byte[] payload = credentials.credentialsFor(
                player.getUUID().toString(), player.getGameProfile().getName());
        if (payload == null) {
            return;
        }
        // 发包走 vanilla custom payload：connection.send 与该包构造在 1.18.2 稳定，
        // 避开 Forge 各 40.x 小版本间 channel.send 签名的反复。没装 mod 的客户端
        // 收到未知频道会静默忽略。
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        player.connection.send(new ClientboundCustomPayloadPacket(CHANNEL, buf));
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server.onServerStarted(VersionServer.mcPort(event.getServer()));
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        server.onServerStopping();
    }

    static String modVersion() {
        return net.minecraftforge.fml.ModList.get().getModContainerById(NetherwayModern.MODID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("0.0.0");
    }
}
