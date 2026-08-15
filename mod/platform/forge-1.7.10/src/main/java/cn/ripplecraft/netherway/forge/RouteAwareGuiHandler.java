package cn.ripplecraft.netherway.forge;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ServerListEntryNormal;
import net.minecraft.client.gui.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.network.OldServerPinger;
import net.minecraftforge.client.event.GuiOpenEvent;

/**
 * 给原版多人服务器界面加一层运行期路由，不改它持有的 {@link ServerData}。
 *
 * <p>Forge 1.7.10 没有“即将连接服务器”事件；最小侵入的做法是只替换原版
 * {@link GuiMultiplayer}（其他 mod 的自定义子类原样放行）。接管两个动作：
 * 点击连接（覆写所有单击、回车和双击最终都会经过的 {@code func_146796_h()}）
 * 与列表的延迟探测（覆写 {@code func_146789_i()} 换上路由感知的 pinger）。
 * 两者查同一张 {@link WarmupEntryRouter} 路由表，所以玩家看到的延迟读数
 * 永远对应点击后实际走的那条路：隧道 READY 显示直连延迟，没打通自然回落到
 * 中转读数。所有覆盖都经临时副本完成，真实条目的地址从不改写，随后任何
 * 图标保存、编辑或排序都只会把真实入口写回 {@code servers.dat}。
 */
public final class RouteAwareGuiHandler {

    private final WarmupEntryRouter routes;
    private final ForgeClientBridge bridge;

    RouteAwareGuiHandler(WarmupEntryRouter routes, ForgeClientBridge bridge) {
        this.routes = routes;
        this.bridge = bridge;
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (!routes.replacesEntries() || event.gui == null
                || event.gui.getClass() != GuiMultiplayer.class) {
            return;
        }
        try {
            GuiScreen parent = ReflectionHelper.getPrivateValue(
                    GuiMultiplayer.class, (GuiMultiplayer) event.gui, "field_146798_g");
            event.gui = new RouteAwareGuiMultiplayer(parent, routes, bridge);
        } catch (RuntimeException e) {
            bridge.warn("无法接管原版服务器列表，本次仍使用原入口", e);
        }
    }

    private static final class RouteAwareGuiMultiplayer extends GuiMultiplayer {

        private final WarmupEntryRouter routes;
        private final ForgeClientBridge bridge;
        private RouteAwarePinger routePinger;
        private boolean repingBroken;

        RouteAwareGuiMultiplayer(GuiScreen parent, WarmupEntryRouter routes,
                                 ForgeClientBridge bridge) {
            super(parent);
            this.routes = routes;
            this.bridge = bridge;
        }

        /** 原版的按钮、回车与双击最终都会走这里。 */
        @Override
        public void func_146796_h() {
            ServerData original;
            try {
                ServerSelectionList selection = ReflectionHelper.getPrivateValue(
                        GuiMultiplayer.class, this, "field_146803_h");
                int index = selection == null ? -1 : selection.func_148193_k();
                GuiListExtended.IGuiListEntry item = index < 0
                        ? null : selection.getListEntry(index);
                if (!(item instanceof ServerListEntryNormal)) {
                    super.func_146796_h();
                    return;
                }
                original = ((ServerListEntryNormal) item).func_148296_a();
            } catch (RuntimeException e) {
                bridge.warn("读取选中的服务器失败，改用原入口", e);
                super.func_146796_h();
                return;
            }

            WarmupEntryRouter.Route route = routes.resolve(original.serverIP);
            if (route == null) {
                super.func_146796_h();
                return;
            }

            ServerData direct = new ServerData(original.serverName, route.localAddress());
            direct.func_152583_a(original);
            direct.serverIP = route.localAddress();
            copyFmlMetadata(original, direct, bridge);
            bridge.info("服务器列表选择已解析到预热隧道: " + original.serverIP
                    + " → " + direct.serverIP);
            FMLClientHandler.instance().connectToServer(this, direct);
        }

        /** 列表条目经这个 getter 提交延迟探测；换上路由感知的包装。 */
        @Override
        public OldServerPinger func_146789_i() {
            if (routePinger == null) {
                routePinger = new RouteAwarePinger(super.func_146789_i(), routes, bridge);
            }
            return routePinger;
        }

        /** 每 tick 把路由探测的结果镜像回真实条目，并跟进路由表的变化。 */
        @Override
        public void updateScreen() {
            super.updateScreen();
            if (routePinger == null) {
                return;
            }
            routePinger.mirrorResults();
            forceRepingOnRouteChange();
        }

        /**
         * 隧道在列表开着期间转 READY（或失效）时，把相关条目标记回未探测，
         * 下一帧渲染就会按当前路由重新提交探测——读数无需手动刷新即可跟上。
         */
        private void forceRepingOnRouteChange() {
            if (repingBroken) {
                return;
            }
            try {
                ServerList list = ReflectionHelper.getPrivateValue(
                        GuiMultiplayer.class, this, "field_146804_i");
                if (list == null) {
                    return;
                }
                for (int i = 0; i < list.countServers(); i++) {
                    ServerData entry = list.getServerData(i);
                    if (entry != null && entry.field_78841_f
                            && routePinger.routeChangedSincePing(entry)) {
                        entry.field_78841_f = false;
                    }
                }
            } catch (RuntimeException e) {
                repingBroken = true;
                bridge.debug("读取服务器列表失败，路由变化后的延迟读数改由手动刷新更新: " + e);
            }
        }
    }

    /**
     * 往真实条目探测前先查路由：命中就 ping 预热隧道的回环端口，量出来的
     * 就是打洞路径的真实延迟。
     *
     * <p>探测用临时副本发起——真实条目的 {@code serverIP} 全程无人写，落盘
     * 零风险；结果由 {@link #mirrorResults} 每 tick 抄回。两类探测的网络管道
     * 都注册进原版 pinger 实例：{@link GuiMultiplayer} 的收包泵与关屏取消
     * 直接操作私有字段、不经 getter，自建实例的回包永远无人处理。
     * 同步段抛出的异常原样穿透，由列表条目自己的 catch 往真实条目写失败状态，
     * 与原版行为合流。
     */
    private static final class RouteAwarePinger extends OldServerPinger {

        /** 在 {@link #pingedVia} 里表示“这次探测没走路由”。 */
        private static final String DIRECT = "";

        private final OldServerPinger vanilla;
        private final WarmupEntryRouter routes;
        private final ForgeClientBridge bridge;
        /** 真实条目 → 承接探测结果的临时副本。 */
        private final ConcurrentMap<ServerData, ServerData> mirrors =
                new ConcurrentHashMap<ServerData, ServerData>();
        /** 真实条目 → 探测时使用的回环地址。探测在线程池、检查在主线程。 */
        private final ConcurrentMap<ServerData, String> pingedVia =
                new ConcurrentHashMap<ServerData, String>();

        RouteAwarePinger(OldServerPinger vanilla, WarmupEntryRouter routes,
                         ForgeClientBridge bridge) {
            this.vanilla = vanilla;
            this.routes = routes;
            this.bridge = bridge;
        }

        @Override
        public void func_147224_a(ServerData real) throws UnknownHostException {
            WarmupEntryRouter.Route route = routes.resolve(real.serverIP);
            if (route == null) {
                mirrors.remove(real);
                pingedVia.put(real, DIRECT);
                vanilla.func_147224_a(real);
                return;
            }
            pingedVia.put(real, route.localAddress());
            ServerData temp = new ServerData(real.serverName, route.localAddress());
            vanilla.func_147224_a(temp);
            mirrors.put(real, temp);
            bridge.debug("服务器列表延迟探测已解析到预热隧道: " + real.serverIP
                    + " → " + route.localAddress());
        }

        /** 主线程每 tick 调用。跨线程读探测结果与原版同样不加锁，语义一致。 */
        void mirrorResults() {
            for (Map.Entry<ServerData, ServerData> pair : mirrors.entrySet()) {
                ServerData real = pair.getKey();
                ServerData temp = pair.getValue();
                real.pingToServer = temp.pingToServer;
                real.serverMOTD = temp.serverMOTD;
                real.populationInfo = temp.populationInfo;
                real.gameVersion = temp.gameVersion;
                real.field_82821_f = temp.field_82821_f;
                real.field_147412_i = temp.field_147412_i;
                real.func_147407_a(temp.getBase64EncodedIconData());
                copyFmlMetadata(temp, real, bridge);
            }
        }

        /** 该条目上次探测走的路径与现在的路由表是否已对不上；是则清掉记录。 */
        boolean routeChangedSincePing(ServerData real) {
            String recorded = pingedVia.get(real);
            if (recorded == null) {
                return false;
            }
            WarmupEntryRouter.Route route = routes.resolve(real.serverIP);
            String current = route == null ? DIRECT : route.localAddress();
            if (recorded.equals(current)) {
                return false;
            }
            mirrors.remove(real);
            pingedVia.remove(real);
            return true;
        }
    }

    /** 保留 FML 的兼容性/禁止连接判断；复制失败时仍与未 ping 的原版行为一致。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void copyFmlMetadata(ServerData from, ServerData to,
                                        ForgeClientBridge bridge) {
        try {
            Map metadata = ReflectionHelper.getPrivateValue(FMLClientHandler.class,
                    FMLClientHandler.instance(), "serverDataTag");
            if (metadata != null) {
                synchronized (metadata) {
                    if (metadata.containsKey(from)) {
                        metadata.put(to, metadata.get(from));
                    }
                }
            }
        } catch (RuntimeException e) {
            bridge.debug("复制 FML 服务器列表元数据失败: " + e);
        }
    }
}
