package cn.ripplecraft.netherway.modern.mixin;

import java.net.SocketAddress;

/**
 * 由 {@code ConnectionAccessorMixin} 混入 {@code net.minecraft.network.Connection}
 * 的 setter 接口，让 {@link cn.ripplecraft.netherway.modern.SnifferCore} 能改写
 * PROXY protocol 剥头后的真实来源地址，而不必碰反射字符串。
 *
 * <p>放在非 mixin 包，好让 SnifferCore 直接 import 并 cast；运行期 Connection
 * 实例因 Mixin 声明而实现本接口。字段的三套运行时名（mojmap/SRG/intermediary）
 * 由 Mixin 的 {@code @Accessor} 自动 remap。
 */
public interface ConnectionAddressAccessor {

    void netherway$setAddress(SocketAddress address);
}
