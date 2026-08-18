package cn.ripplecraft.netherway.modern.mixin;

import java.net.SocketAddress;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 给 {@code Connection} 装一个写 {@code address} 字段的口子，供 PROXY
 * protocol 剥头后回写真实来源地址。字段名 {@code address} 在 mojmap 下
 * 1.16.5–1.20.1 稳定；Mixin 按各 loader 的运行时映射自动 remap。
 */
@Mixin(Connection.class)
public interface ConnectionAccessorMixin extends ConnectionAddressAccessor {

    @Override
    @Accessor("address")
    void netherway$setAddress(SocketAddress address);
}
