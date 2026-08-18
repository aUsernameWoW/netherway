package cn.ripplecraft.netherway.modern.mixin;

import cn.ripplecraft.netherway.modern.SnifferCore;
import io.netty.channel.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在服务端每条 accept 连接的 pipeline 初始化尾部挂上嗅探 handler。
 *
 * <p>目标是 {@code ServerConnectionListener} 里那个 TCP
 * {@code ChannelInitializer} 匿名内部类（编号 {@code $1}，ViaFabric 从
 * 1.16.5 用到 1.20.x 的同款注入点，三个目标版本零差异）。选它而非反射
 * endpoints 列表：Mixin 自动处理三套运行时映射，且天然只命中真实 TCP
 * 连接、避开内存/LAN 通道。TAIL 注入保证此刻 MC 的默认 handler 已装齐，
 * {@link SnifferCore#attach} 里的 addFirst 才能排到 timeout/legacy_query 之前。
 *
 * <p>Context 未激活时 attach 是空操作，等同 1.7.10 版「装上之前的连接不管」。
 */
@Mixin(targets = "net.minecraft.server.network.ServerConnectionListener$1")
public class ServerConnectionInitMixin {

    @Inject(method = "initChannel", at = @At("TAIL"), require = 0)
    private void netherway$attach(Channel channel, CallbackInfo ci) {
        SnifferCore.attach(channel);
    }
}
