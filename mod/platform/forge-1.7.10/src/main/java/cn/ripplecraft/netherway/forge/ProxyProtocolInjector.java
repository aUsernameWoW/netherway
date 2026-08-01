package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.ProxyProtocol;
import cpw.mods.fml.relauncher.ReflectionHelper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.NetworkSystem;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 把 PROXY protocol 剥头组件挂进服务端的 Netty 接入链。
 *
 * <p>挂载点是监听端点的 server channel：accept 出来的每个连接会以
 * {@link Channel} 消息的形式流过它的 pipeline（这正是 Netty 自己的
 * ServerBootstrapAcceptor 的工作方式），在 acceptor 之前插一个拦截器，
 * 就能抢在 MC 的 ChannelInitializer 之前往新连接的 pipeline 头部塞
 * 剥头 handler——字节因此先经我们、再进 legacy_query/splitter。
 *
 * <p>两处反射都带 MCP 与 SRG 双名（开发环境用前者，线上重混淆后用后者）：
 * {@code NetworkSystem.endpoints} = {@code field_151274_e}，
 * {@code NetworkManager.socketAddress} = {@code field_150743_l}。
 * 后者非 final（原版在 channelActive 里赋值），Java 8–25 上都能安全改写。
 *
 * <p>信任边界：只对来自回环地址的连接嗅探——serve 侧的 frp 是从本机拨入的，
 * 而 MC 端口若同时暴露在局域网，任何邻居都能伪造一个头把自己装成别人。
 * 非回环连接原样放行，头也不剥（真 MC 客户端本就不该发这种字节）。
 */
final class ProxyProtocolInjector {

    private static final Logger LOG = LogManager.getLogger(Netherway.MODID);
    private static final String ACCEPTOR_NAME = "netherway_pp_acceptor";
    private static final String HANDLER_NAME = "netherway_proxy_protocol";

    private ProxyProtocolInjector() {
    }

    /**
     * 在 FMLServerStartedEvent 后调用（此时监听端点已绑定完毕）。
     * 失败只记日志——但要把「serve 还在发头」的后果说清楚。
     */
    static void install(MinecraftServer server) {
        try {
            NetworkSystem system = server.func_147137_ag();
            List<?> endpoints = ReflectionHelper.getPrivateValue(
                    NetworkSystem.class, system, "endpoints", "field_151274_e");
            int hooked = 0;
            synchronized (endpoints) {
                for (Object o : endpoints) {
                    Channel ch = ((ChannelFuture) o).channel();
                    if (ch.pipeline().get(ACCEPTOR_NAME) == null) {
                        ch.pipeline().addFirst(ACCEPTOR_NAME, new Acceptor());
                        hooked++;
                    }
                }
            }
            if (hooked > 0) {
                LOG.info("PROXY protocol 剥头已挂载到 {} 个监听端点，"
                        + "经隧道进来的连接将以真实来源地址示人", hooked);
            } else {
                LOG.warn("没有找到可挂载的监听端点，PROXY protocol 剥头未生效");
            }
        } catch (Exception e) {
            LOG.warn("PROXY protocol 剥头挂载失败（MC 内部结构与预期不符？）。"
                    + "serve 侧若开着 -proxy-protocol 请先关掉，否则玩家会连不上", e);
        }
    }

    /** 拦在 ServerBootstrapAcceptor 前面，给每个新 accept 的连接装剥头 handler。 */
    private static final class Acceptor extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Channel) {
                ((Channel) msg).pipeline().addFirst(HANDLER_NAME, new HeaderHandler());
            }
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * 连接最初的字节流过这里：攒够下定论的量，剥头（若有）、把真实来源
     * 写回 NetworkManager，然后把自己摘出 pipeline，之后零开销。
     *
     * <p>刻意不用 ByteToMessageDecoder：1.7.10 的 Netty 4.0.x 太老，
     * 「decode 中途移除自己」的边角行为在后续版本里修过不止一次，
     * 手动攒缓冲反而没有历史包袱。解析器保证 NEED_MORE 有界
     * （v1 至多 107 字节、v2 至多 16+4096），缓冲不会被撑爆。
     */
    private static final class HeaderHandler extends ChannelInboundHandlerAdapter {

        private ByteBuf pending;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf)) {
                ctx.fireChannelRead(msg);
                return;
            }
            ByteBuf in = (ByteBuf) msg;

            SocketAddress remote = ctx.channel().remoteAddress();
            boolean loopback = remote instanceof InetSocketAddress
                    && ((InetSocketAddress) remote).getAddress() != null
                    && ((InetSocketAddress) remote).getAddress().isLoopbackAddress();
            if (!loopback && pending == null) {
                // 信任边界：非回环来源不嗅探，原样放行并退出链路
                ctx.pipeline().remove(this);
                ctx.fireChannelRead(in);
                return;
            }

            if (pending == null) {
                pending = ctx.alloc().buffer(256);
            }
            pending.writeBytes(in);
            in.release();

            byte[] peek = new byte[pending.readableBytes()];
            pending.getBytes(pending.readerIndex(), peek);
            ProxyProtocol.Result r = ProxyProtocol.parse(peek);
            switch (r.status) {
                case NEED_MORE:
                    return;
                case NOT_PRESENT:
                    passRemaining(ctx);
                    return;
                case INVALID:
                    // 前缀像头但内容坏了。真 MC 客户端不会发这种字节，
                    // 多半是 serve 侧与本侧版本/配置岔了，断开最诚实。
                    LOG.warn("来自 {} 的连接带着非法的 PROXY 头（{}），已断开", remote, r.error);
                    releasePending();
                    ctx.close();
                    return;
                case PRESENT:
                default:
                    pending.skipBytes(r.headerLength);
                    if (r.source != null) {
                        rewriteRemote(ctx, r.source);
                    }
                    passRemaining(ctx);
            }
        }

        /** 把攒下的剩余字节交还 pipeline，并把自己摘掉。 */
        private void passRemaining(ChannelHandlerContext ctx) {
            ByteBuf rest = pending;
            pending = null;
            ctx.pipeline().remove(this);
            if (rest.isReadable()) {
                ctx.fireChannelRead(rest);
            } else {
                rest.release();
            }
        }

        /**
         * 把真实来源写回 NetworkManager（1.7.10 里它就是 packet_handler）。
         * 原版在 channelActive 里已把 socketAddress 设成了回环地址，这里发生
         * 在首个字节之后，必然晚于它——登录日志与封禁看到的就是改写后的值。
         */
        private void rewriteRemote(ChannelHandlerContext ctx, InetSocketAddress source) {
            ChannelHandler tail = ctx.pipeline().get("packet_handler");
            if (tail instanceof NetworkManager) {
                ReflectionHelper.setPrivateValue(NetworkManager.class, (NetworkManager) tail,
                        source, "socketAddress", "field_150743_l");
                LOG.debug("PROXY 头已剥离，连接真实来源 {}", source);
            } else {
                LOG.warn("pipeline 里没有 packet_handler，真实来源 {} 未能写回（仅剥头）", source);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            releasePending();
            ctx.fireChannelInactive();
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            releasePending();
        }

        private void releasePending() {
            if (pending != null) {
                pending.release();
                pending = null;
            }
        }
    }
}
