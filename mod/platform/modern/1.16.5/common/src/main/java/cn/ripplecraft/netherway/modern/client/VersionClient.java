package cn.ripplecraft.netherway.modern.client;

import cn.ripplecraft.netherway.core.SessionIdentity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.TextComponent;

/**
 * 客户端逐版差异（1.16.5）：发起连接、聊天文本、会话身份、游戏语言、
 * 直连条目工厂。Forge 与 Fabric 客户端入口都从这里取。
 */
public final class VersionClient {

    private VersionClient() {
    }

    public static GameOps ops() {
        return new Ops();
    }

    /** 会话身份用于预取；离线会话也能预取（预下发不验证身份）。 */
    public static SessionIdentity session() {
        User user = Minecraft.getInstance().getUser();
        if (user == null) {
            return SessionIdentity.of("", "");
        }
        return SessionIdentity.of(user.getName(), user.getUuid());
    }

    /**
     * 当前游戏语言（如 zh_cn），用于 cfg 的 auto 精化。
     * 1.16.5 mojmap 没有 LanguageManager.getSelected()，语言直接是
     * Options 的公共字段 {@code languageCode}。
     * CI 核对：字段名 languageCode 在 1.16.5 mojmap 的 Options 上应存在且可见。
     */
    public static String gameLanguage() {
        try {
            return Minecraft.getInstance().options.languageCode;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static DirectEntryFactory directFactory() {
        return DirectServerEntry::new;
    }

    private static final class Ops implements GameOps {

        @Override
        public void connect(String host, int port) {
            Minecraft mc = Minecraft.getInstance();
            String ip = host + ":" + port;
            // 1.16.5 无静态 ConnectScreen.startConnecting（那是 1.17+ 才引入的入口）。
            // 连接由 ConnectScreen 构造器内部发起：它先 clearLevel 退出当前世界、
            // setCurrentServer(serverData)，再起连接线程解析 serverData.ip。
            // ServerData 必须非空；1.16.5 构造器第三参是 boolean（局域网标记），传 false。
            ServerData data = new ServerData("Netherway", ip, false);
            // CI 核对：ConnectScreen 构造器签名（本版最不确定处）。首选按下面
            //   (Screen parent, Minecraft mc, ServerData serverData)
            // 若反编译显示为 (Screen, Minecraft, String host, int port)：改成
            //   new ConnectScreen(parent, mc, host, port)（此时可删掉上面的 ServerData）；
            // 若某快照要求 ServerAddress：改成
            //   new ConnectScreen(parent, mc,
            //       net.minecraft.client.multiplayer.ServerAddress.parseString(ip), data)
            //   注意 1.16.5 的 ServerAddress 在 .multiplayer 包（非 .resolver）。
            mc.setScreen(new ConnectScreen(
                    new JoinMultiplayerScreen(new TitleScreen()), mc, data));
        }

        @Override
        public void sendChat(String message) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                // 1.16.5 无 Component.literal 工厂，用 new TextComponent(message)。
                // withStyle(ChatFormatting) 在 1.16.5 的 MutableComponent 上存在，
                // 保留 1.20.1 的绿色高亮行为。displayClientMessage(Component, boolean) 存在。
                mc.player.displayClientMessage(
                        new TextComponent(message).withStyle(ChatFormatting.GREEN), false);
            }
        }
    }
}
