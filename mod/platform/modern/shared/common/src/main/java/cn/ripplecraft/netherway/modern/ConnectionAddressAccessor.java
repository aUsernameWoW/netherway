package cn.ripplecraft.netherway.modern;

import java.net.SocketAddress;

/**
 * 由 {@code ConnectionAccessorMixin} 混入 {@code net.minecraft.network.Connection}
 * 的 setter 接口，让 {@link cn.ripplecraft.netherway.modern.SnifferCore} 能改写
 * PROXY protocol 剥头后的真实来源地址，而不必碰反射字符串。
 *
 * <p>必须放在 mixin 包（{@code netherway.mixins.json} 的 {@code package}）之外：
 * Mixin 拒绝加载 mixin 包里的任何非 mixin 类（IllegalClassLoadError），而
 * Connection 实现本接口，定义 Connection 时就要加载它——放错包的后果是所有
 * modern jar 一启动就崩（2.0.1 及以前确实如此）。
 *
 * <p>SnifferCore 直接 import 并 cast；运行期 Connection 实例因 Mixin 声明而
 * 实现本接口。字段的三套运行时名（mojmap/SRG/intermediary）
 * 由 Mixin 的 {@code @Accessor} 自动 remap。
 */
public interface ConnectionAddressAccessor {

    void netherway$setAddress(SocketAddress address);
}
