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

/**
 * 客户端逐版差异（1.18.2）：发起连接、聊天文本、会话身份、游戏语言、
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
            // 1.18.2：languageCode 是 Options 上的 public String 字段
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
            // 1.18.2 的 startConnecting 自己会先退出当前世界；ServerData 传非空即可，
            // 1.18.2 构造是 (name, ip, boolean)，startConnecting 是 4 参、无 quickPlay。
            ServerData data = new ServerData("Netherway", ip, false);
            ConnectScreen.startConnecting(
                    new JoinMultiplayerScreen(new TitleScreen()), mc,
                    ServerAddress.parseString(ip), data);
        }

        @Override
        public void sendChat(String message) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                // 1.18.2 用 TextComponent（Component.literal 是 1.19.4+）；
                // TextComponent 是 MutableComponent，withStyle 链式返回 Component。
                mc.player.displayClientMessage(
                        new net.minecraft.network.chat.TextComponent(message)
                                .withStyle(ChatFormatting.GREEN), false);
            }
        }
    }
}
