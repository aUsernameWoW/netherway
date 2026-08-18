package cn.ripplecraft.netherway.modern.client.mixin;

import cn.ripplecraft.netherway.modern.client.ClientRouting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 覆盖模式下，把「加入服务器」的目标地址在真正连接前改写到 READY 预热隧道的
 * 回环端口。改的是 startConnecting 收到的 ServerAddress 形参，展示用的
 * ServerData（名字、图标）与 servers.dat 里的真实入口都不动。
 *
 * <p>选这里而非「取消 ConnectScreen 打开」：各版本连接线程都在 screen-open
 * 事件可见之前/之外启动，取消打开拦不住连接。改形参覆盖面更大——其他 mod
 * 自绘界面直接连服也走这条静态入口。
 *
 * <p>1.20.1 签名 {@code startConnecting(Screen, Minecraft, ServerAddress,
 * ServerData, boolean)}，ServerAddress 在 LVT 索引 2。
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    @ModifyVariable(method = "startConnecting", at = @At("HEAD"), argsOnly = true, index = 2)
    private static ServerAddress netherway$reroute(ServerAddress address, Screen screen,
            Minecraft mc, ServerAddress serverAddress, ServerData serverData, boolean quickPlay) {
        if (serverData == null || !ClientRouting.active()) {
            return address;
        }
        String loopback = ClientRouting.resolve(serverData.ip);
        return loopback == null ? address : ServerAddress.parseString(loopback);
    }
}
