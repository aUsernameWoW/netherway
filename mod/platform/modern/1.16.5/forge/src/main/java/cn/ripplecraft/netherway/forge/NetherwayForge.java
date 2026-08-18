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
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.event.EventNetworkChannel;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forge 1.16.5 入口（服务端半边 + 公共装配）。客户端半边在
 * {@code NetherwayForgeClient}，经 {@link DistExecutor} 只在物理客户端加载。
 *
 * <p>与 Fabric 入口对称：同一份 {@link ServerRuntime}/{@link CredentialService}/
 * {@link UpgradeReportService} 共享逻辑，这里只做 Forge 事件与频道的接线。
 * 频道声明为 optional（放行 ABSENT/ACCEPTVANILLA）并注册 DisplayTest，
 * 保证没装 mod 的客户端照常进服——本 mod 是纯增强，绝不构成准入门槛。
 *
 * <p><b>1.16.5 与 1.18+ 的 Forge 网络/事件 API 差异（本文件核心 delta）：</b>
 * <ul>
 *   <li>网络类在 {@code net.minecraftforge.fml.network.*}（1.18 起搬到
 *       {@code net.minecraftforge.network.*}）：NetworkRegistry.ChannelBuilder、
 *       {@code fml.network.event.EventNetworkChannel}、{@code fml.network.FMLNetworkConstants}。</li>
 *   <li>DisplayTest 经 {@code ExtensionPoint.DISPLAYTEST}（枚举）+ 一个返回
 *       {@code Pair<Supplier<String>, BiPredicate>} 的 Supplier 注册，
 *       常量是 {@code FMLNetworkConstants.IGNORESERVERONLY}（1.18+ 才叫 NetworkConstants，
 *       DisplayTest 也才变成 {@code IExtensionPoint.DisplayTest} 记录类）。</li>
 *   <li>服务器生命周期事件在 {@code fml.event.server.FMLServerStartedEvent/FMLServerStoppingEvent}
 *       （1.18+ 改名 {@code event.server.ServerStartedEvent/ServerStoppingEvent}）。</li>
 *   <li>{@code PlayerEvent.PlayerLoggedInEvent.getPlayer()}（1.17+ 才是 getEntity()）。</li>
 * </ul>
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
        this.server = new ServerRuntime(config, wiring);
        this.credentials = new CredentialService(config);
        this.reports = new UpgradeReportService(config);

        // optional 频道：放行 ABSENT/ACCEPTVANILLA，未装 mod 的客户端不被握手拒绝。
        // CI 核对：1.16.5 的 NetworkRegistry 未必有 acceptMissingOr(String)（那是较新
        //   Forge 才加的 helper）。这里用最宽松的 s -> true 兜底——任何版本（含缺失/
        //   vanilla）都放行，达成「纯增强、绝不拒绝握手」的目的。若该版本确有
        //   NetworkRegistry.acceptMissingOr，可换成
        //   .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
        //   以获得「拒绝错误版本、放行缺失」的更精确语义。
        this.channel = NetworkRegistry.ChannelBuilder
                .named(CHANNEL)
                .networkProtocolVersion(() -> PROTOCOL)
                .clientAcceptedVersions(s -> true)
                .serverAcceptedVersions(s -> true)
                .eventNetworkChannel();
        // 服务端收客户端回传的升级结果。
        // CI 核对：1.16.5 的 EventNetworkChannel.addListener 消费 NetworkEvent 事件，
        //   event.getSource() 若返回 Supplier<NetworkEvent.Context>（部分 Forge 版本如此）
        //   则下面各处需改成 event.getSource().get().xxx()。event.getPayload() 在 mojmap
        //   1.16.5 返回 net.minecraft.network.FriendlyByteBuf。
        channel.addListener(event -> {
            net.minecraftforge.fml.network.NetworkEvent.Context ctx = event.getSource().get();
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

        // DisplayTest：服务器不因缺少本 mod 而在客户端列表被标红。
        // CI 核对：ExtensionPoint.DISPLAYTEST 的泛型是
        //   Pair<Supplier<String>, BiPredicate<String, Boolean>>；registerExtensionPoint
        //   第二参是返回该 Pair 的 Supplier。org.apache.commons.lang3.tuple.Pair 由 Forge 提供。
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
                () -> Pair.of(
                        () -> FMLNetworkConstants.IGNORESERVERONLY,
                        (remote, isServer) -> true));

        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        DistExecutor.safeRunWhenOn(Dist.CLIENT,
                () -> () -> NetherwayForgeClient.init(config, channel));
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        // 1.16.5：PlayerLoggedInEvent.getPlayer()（1.17+ 才是 getEntity()）。
        if (!(event.getPlayer() instanceof ServerPlayer)) {
            return;
        }
        ServerPlayer player = (ServerPlayer) event.getPlayer();
        byte[] payload = credentials.credentialsFor(
                player.getUUID().toString(), player.getGameProfile().getName());
        if (payload == null) {
            return;
        }
        // 发包走 vanilla custom payload：connection.send 与该包构造在 1.16.5 稳定。
        // 没装 mod 的客户端收到未知频道会静默忽略。
        // CI 核对：ClientboundCustomPayloadPacket(ResourceLocation, FriendlyByteBuf)
        //   构造器在 1.16.5 mojmap 存在（buf 类型 net.minecraft.network.FriendlyByteBuf）。
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        player.connection.send(new ClientboundCustomPayloadPacket(CHANNEL, buf));
    }

    @SubscribeEvent
    public void onServerStarted(FMLServerStartedEvent event) {
        server.onServerStarted(VersionServer.mcPort(event.getServer()));
    }

    @SubscribeEvent
    public void onServerStopping(FMLServerStoppingEvent event) {
        server.onServerStopping();
    }

    static String modVersion() {
        return net.minecraftforge.fml.ModList.get().getModContainerById(NetherwayModern.MODID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("0.0.0");
    }
}
