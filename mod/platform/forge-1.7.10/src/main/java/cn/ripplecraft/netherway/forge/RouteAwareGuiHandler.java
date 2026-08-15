package cn.ripplecraft.netherway.forge;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import java.util.Map;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ServerListEntryNormal;
import net.minecraft.client.gui.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.client.event.GuiOpenEvent;

/**
 * 给原版多人服务器界面加一层点击时路由，不改它持有的 {@link ServerData}。
 *
 * <p>Forge 1.7.10 没有“即将连接服务器”事件；最小侵入的做法是只替换原版
 * {@link GuiMultiplayer}（其他 mod 的自定义子类原样放行），并覆写所有单击、
 * 回车和双击最终都会经过的 {@code func_146796_h()}。用于连接的是临时副本，
 * 因此随后任何图标保存、编辑或排序都只会把真实入口写回 {@code servers.dat}。
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
            copyFmlMetadata(original, direct);
            bridge.info("服务器列表选择已解析到预热隧道: " + original.serverIP
                    + " → " + direct.serverIP);
            FMLClientHandler.instance().connectToServer(this, direct);
        }

        /** 保留 FML 的兼容性/禁止连接判断；复制失败时仍与未 ping 的原版行为一致。 */
        @SuppressWarnings({"rawtypes", "unchecked"})
        private void copyFmlMetadata(ServerData original, ServerData direct) {
            try {
                Map metadata = ReflectionHelper.getPrivateValue(FMLClientHandler.class,
                        FMLClientHandler.instance(), "serverDataTag");
                if (metadata != null) {
                    synchronized (metadata) {
                        if (metadata.containsKey(original)) {
                            metadata.put(direct, metadata.get(original));
                        }
                    }
                }
            } catch (RuntimeException e) {
                bridge.debug("复制 FML 服务器列表元数据失败: " + e);
            }
        }
    }
}
