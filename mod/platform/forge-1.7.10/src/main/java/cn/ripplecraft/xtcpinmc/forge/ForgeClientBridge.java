package cn.ripplecraft.xtcpinmc.forge;

import cn.ripplecraft.xtcpinmc.core.ClientBridge;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.Unpooled;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * {@link ClientBridge} 的 Forge 1.7.10 实现——整个 mod 里唯一
 * 大量触碰 Minecraft 客户端类的地方。
 *
 * <p>主线程派发用 tick 队列而不是 1.7.10 里尚无友好名字的
 * {@code Minecraft.func_152344_a}：队列不依赖任何混淆名，
 * 由 {@link ClientEvents#onClientTick} 每 tick 开头排空。
 */
public final class ForgeClientBridge implements ClientBridge {

    private static final Logger LOG = LogManager.getLogger(XtcpInMc.MODID);

    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<Runnable>();
    private final FMLEventChannel channel;
    private final boolean verboseLog;

    /** 升级重定向的目标端口；0 表示当前没有进行中的重定向。 */
    private volatile int redirectPort;

    public ForgeClientBridge(FMLEventChannel channel, boolean verboseLog) {
        this.channel = channel;
        this.verboseLog = verboseLog;
    }

    @Override
    public void runOnGameThread(Runnable task) {
        tasks.add(task);
    }

    /** 每个客户端 tick 开头由 {@link ClientEvents} 调用。 */
    void drainTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.warn("主线程任务执行失败", e);
            }
        }
    }

    boolean redirectInFlight() {
        return redirectPort != 0;
    }

    /**
     * 新连接建立时由 {@link ClientEvents} 调用：
     * 是我们发起的重定向则返回 true 并结束跟踪。
     *
     * <p>比对回环地址与端口，而不是只信一个布尔标志——重定向失败后
     * 玩家手动连别的服务器时，标志还立着，只有地址能识破这不是我们的连接。
     */
    boolean redirectLanded(NetworkManager manager) {
        int expected = redirectPort;
        if (expected == 0) {
            return false;
        }
        redirectPort = 0;
        SocketAddress remote = manager == null ? null : manager.getSocketAddress();
        if (remote instanceof InetSocketAddress) {
            InetSocketAddress addr = (InetSocketAddress) remote;
            boolean landed = addr.getPort() == expected
                    && addr.getAddress() != null
                    && addr.getAddress().isLoopbackAddress();
            debug("新连接 " + addr + (landed
                    ? " 是我们发起的直连切换"
                    : " 与直连切换无关（期望回环端口 " + expected + "）"));
            return landed;
        }
        debug("新连接的地址类型无法识别，视作与直连切换无关（期望回环端口 " + expected + "）");
        return false;
    }

    @Override
    public void connectTo(String host, int port) {
        debug("断开当前连接，重连到 " + host + ":" + port);
        // 标志必须在断开动作之前立起来：断开事件在 netty 线程异步到达，
        // 到达时 ClientEvents 要靠它认出「这是我们自己造成的断开」
        redirectPort = port;

        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        if (world != null) {
            world.sendQuittingDisconnectingPacket();
        }
        mc.loadWorld((WorldClient) null);
        mc.displayGuiScreen(new GuiConnecting(
                new GuiMultiplayer(new GuiMainMenu()), mc, host, port));
    }

    @Override
    public void notifyPlayer(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(
                    EnumChatFormatting.GREEN + "[直连] "
                            + EnumChatFormatting.RESET + message));
        }
    }

    @Override
    public void sendToServer(final byte[] payload) {
        // 升级线程会调进来，经 tick 队列转到主线程再发；
        // 没有连接时安静丢弃——回执是尽力而为的诊断信息
        runOnGameThread(new Runnable() {
            @Override
            public void run() {
                if (Minecraft.getMinecraft().getNetHandler() == null) {
                    debug("当前没有连接，结果回执未发送");
                    return;
                }
                channel.sendToServer(new FMLProxyPacket(
                        Unpooled.wrappedBuffer(payload), XtcpInMc.CHANNEL));
            }
        });
    }

    @Override
    public Path cacheDirectory() {
        return new File(Minecraft.getMinecraft().mcDataDir, "xtcpinmc").toPath();
    }

    @Override
    public void info(String message) {
        LOG.info(message);
    }

    @Override
    public void warn(String message, Throwable error) {
        LOG.warn(message, error);
    }

    @Override
    public void debug(String message) {
        // 1.7.10 默认的 log4j 配置不显示 DEBUG 级别，verbose 时提到 INFO，
        // 让玩家不用动日志配置就能看到完整的打洞过程
        if (verboseLog) {
            LOG.info(message);
        } else {
            LOG.debug(message);
        }
    }
}
