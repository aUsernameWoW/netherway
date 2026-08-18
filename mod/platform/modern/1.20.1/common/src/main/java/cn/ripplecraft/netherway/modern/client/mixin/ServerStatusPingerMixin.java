package cn.ripplecraft.netherway.modern.client.mixin;

import cn.ripplecraft.netherway.modern.client.ClientRouting;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 覆盖模式下，让服务器列表的延迟探测走 READY 预热隧道的回环端口——量出来的
 * 就是打洞路径的真实延迟。做法是重定向 pingServer 内部把条目 ip 解析成
 * ServerAddress 的那一次调用：探测改发回环，而结果仍写回传入的真实
 * ServerData（延迟/MOTD/图标照常更新，ip 从不改写、落盘零风险）。
 *
 * <p>1.20.1 的 ServerAddress 在 {@code net.minecraft.client.multiplayer.resolver}
 * 包，parseString 的方法描述符逐版不同，故本 Mixin 逐版本各一份。
 */
@Mixin(ServerStatusPinger.class)
public class ServerStatusPingerMixin {

    @Redirect(method = "pingServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;"
                    + "parseString(Ljava/lang/String;)"
                    + "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;"))
    private ServerAddress netherway$reroutePing(String original) {
        String loopback = ClientRouting.resolve(original);
        return ServerAddress.parseString(loopback != null ? loopback : original);
    }
}
