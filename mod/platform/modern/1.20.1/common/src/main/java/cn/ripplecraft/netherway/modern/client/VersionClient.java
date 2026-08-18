package cn.ripplecraft.netherway.modern.client;

import cn.ripplecraft.netherway.core.SessionIdentity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * 客户端逐版差异（1.20.1）：发起连接、聊天文本、会话身份、游戏语言、
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

    /** 当前游戏语言（如 zh_cn），用于 cfg 的 auto 精化。 */
    public static String gameLanguage() {
        try {
            return Minecraft.getInstance().getLanguageManager().getSelected();
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
            // 1.20.1 的 startConnecting 自己会先 clearLevel 退出当前世界；
            // ServerData 必须非空（quickPlayLog 无空判），boolean 传 false。
            // isLan=false（ServerData.Type 枚举是 1.20.2 才有的）
            ServerData data = new ServerData("Netherway", ip, false);
            ConnectScreen.startConnecting(
                    new JoinMultiplayerScreen(new TitleScreen()), mc,
                    ServerAddress.parseString(ip), data, false);
        }

        @Override
        public void sendChat(String message) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal(message).withStyle(ChatFormatting.GREEN), false);
            }
        }
    }
}
