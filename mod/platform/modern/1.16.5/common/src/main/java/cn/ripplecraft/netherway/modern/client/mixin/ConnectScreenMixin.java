package cn.ripplecraft.netherway.modern.client.mixin;

import cn.ripplecraft.netherway.modern.client.ClientRouting;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 覆盖模式下，把「加入服务器」的目标地址在真正连接前改写到 READY 预热隧道的
 * 回环端口。改的只是本次连接用的 ServerAddress，展示用的 ServerData（名字、图标）
 * 与 servers.dat 里的真实入口都不动。
 *
 * <p><b>1.16.5 没有静态 {@code ConnectScreen.startConnecting}</b>（那是 1.17+ 才有的
 * 入口），连接由 {@code ConnectScreen} 的构造器内部发起。故本版把 1.20.1 打在
 * startConnecting 形参上的改写，下移到构造器里那次
 * {@code ServerAddress.parseString(serverData.ip)}——@Redirect 它，只替换真正拿去
 * 连接的 ServerAddress。
 *
 * <p><b>为什么必须走 @Redirect 而不是替换 ServerData 形参：</b>构造器内部还会
 * {@code setCurrentServer(serverData)}，而 {@code ModernClientBridge.currentServerAddress()}
 * 正是读 {@code getCurrentServer().ip} 求凭证 origin 的（回环会被它当作无效并丢弃）。
 * 若把 ServerData 换成回环副本，origin 推导就废了、凭证落不了盘。只改 ServerAddress
 * 能让 setCurrentServer 仍拿到真实 ServerData——与 1.20.1「只动 ServerAddress」同构。
 * 这也是本版与 ServerStatusPingerMixin 用同一套 @Redirect parseString 手法的原因。
 *
 * <p>CI 核对（本版最不确定的 Mixin，按优先级）：
 * <ol>
 *   <li><b>构造器形参与内部结构</b>：本实现假设 {@code <init>(Screen, Minecraft, ServerData)}
 *       且其内部调用了 {@code ServerAddress.parseString(serverData.ip)}。若反编译显示构造器是
 *       {@code <init>(Screen, Minecraft, String host, int port)}（不含 parseString），本
 *       @Redirect 会「找不到注入点」而报错——改用 @ModifyArgs/@ModifyArg 打在内部
 *       {@code connect(Minecraft, String, int)} 调用的 host/port 上（用
 *       {@code ClientRouting.resolve(host+":"+port)} 拆回环），或 @Inject HEAD + ci.cancel()
 *       后自行发起回环连接。<b>切勿</b>改写 ServerData 形参（见上，会污染 currentServer）。</li>
 *   <li><b>parseString 调用次数</b>：若 {@code <init>} 里对 ServerAddress.parseString 有多次
 *       调用，需加 {@code ordinal}/{@code slice} 定位到解析 serverData.ip 的那一次。</li>
 *   <li><b>ServerAddress 包名</b>：1.16.5 在 {@code net.minecraft.client.multiplayer}
 *       （非 1.17+ 的 .resolver）。方法描述符已按此写。</li>
 *   <li><b>ConnectScreen 包名</b>：1.16.5 mojmap 为 {@code net.minecraft.client.gui.screens}
 *       （与 1.20.1 相同），若快照不同需同步。</li>
 * </ol>
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ServerAddress;"
                    + "parseString(Ljava/lang/String;)"
                    + "Lnet/minecraft/client/multiplayer/ServerAddress;"))
    private ServerAddress netherway$reroute(String original) {
        if (!ClientRouting.active()) {
            return ServerAddress.parseString(original);
        }
        String loopback = ClientRouting.resolve(original);
        return ServerAddress.parseString(loopback != null ? loopback : original);
    }
}
