package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.PreauthProtocol;
import cn.ripplecraft.netherway.core.PreauthService;
import cn.ripplecraft.netherway.core.TlsRecord;
import cpw.mods.fml.relauncher.ReflectionHelper;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.NetworkSystem;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 把首字节嗅探挂进服务端的 Netty 接入链，一个 handler 同时管三件事：
 * 预认证帧（{@link PreauthProtocol}）、frp 控制通道转发（{@link TlsRecord}）
 * 与 PROXY protocol 剥头（{@link cn.ripplecraft.netherway.core.ProxyProtocol}）。
 *
 * <p>三者必须合成一个 handler：它们抢的是同一批「连接最初的字节」，
 * 各挂各的会互相把对方的数据吃掉。
 *
 * <p>其中 frp 控制通道那一路是「会合点内嵌」的落地点：内嵌 frps 只监听回环，
 * 玩家的控制连接靠这里从 Minecraft 端口转发进去。公网那台机器因此对本项目
 * 再无任何要求——不装插件、不必支持 xtcp、不必同版本，能把 TCP 转到
 * Minecraft 端口即可，于是租用他人的隧道服务成为可能。
 *
 * <p>挂载点是监听端点的 server channel：accept 出来的每个连接会以
 * {@link Channel} 消息的形式流过它的 pipeline（这正是 Netty 自己的
 * ServerBootstrapAcceptor 的工作方式），在 acceptor 之前插一个拦截器，
 * 就能抢在 MC 的 ChannelInitializer 之前往新连接的 pipeline 头部塞
 * 嗅探 handler——字节因此先经我们、再进 legacy_query/splitter。
 *
 * <p>两处反射都带 MCP 与 SRG 双名（开发环境用前者，线上重混淆后用后者）：
 * {@code NetworkSystem.endpoints} = {@code field_151274_e}，
 * {@code NetworkManager.socketAddress} = {@code field_150743_l}。
 *
 * <p>信任边界的两条线不同：<b>PROXY 头只信回环</b>（frp 从本机拨入，
 * 而头是谁都能伪造的，局域网邻居能借它冒充任意来源地址）；<b>预认证帧接受
 * 任何来源</b>——预下发不做身份验证，准入交给 MC 服务端自己的白名单与正版验证。
 */
final class ConnectionSniffer {

    private static final Logger LOG = LogManager.getLogger(Netherway.MODID);
    private static final String ACCEPTOR_NAME = "netherway_acceptor";
    private static final String HANDLER_NAME = "netherway_sniffer";

    /**
     * 一条预认证连接从建立到完成的宽限。单步请求-响应，给足余量；
     * 超时就断，半开连接不能在 MC 端口上堆积。
     *
     * <p>这个值比 MC 自己的读超时（FMLNetworkHandler.READ_TIMEOUT，默认
     * 30 秒）更宽，能生效的前提是进入 PREAUTH 时已把下游 handler 摘掉
     * （{@link Sniffer#takeover}）：独占后不再 fireChannelRead，MC 的
     * ReadTimeoutHandler 收不到读事件就永远不重置计时，不摘的话 30 秒
     * 一到先被它掐断，这里的宽限永远轮不上。
     */
    private static final int EXCHANGE_TIMEOUT_SECONDS = 40;

    /** 同时在处理的预认证请求上限。不设限的话一波并发就能把线程池拖垮。 */
    private static final int MAX_CONCURRENT_PREAUTH = 8;

    /**
     * RELAY 模式下、会合点还没接上时允许攒的字节上限。
     *
     * <p>判定为 frp 控制通道后会立刻停读并去拨会合点，这期间只可能再收到
     * 已经排队的那一两批读事件，正常量级是一个 TLS ClientHello（几百字节）。
     * 给到 64 KB 是为了拨号卡住时也不会无界增长，而不是流控——真正的背压
     * 在接上之后由对端可写性驱动。
     */
    private static final int RELAY_PENDING_MAX = 64 * 1024;

    private ConnectionSniffer() {
    }

    /** 嗅探器的运行期依赖，install 时组装一次。 */
    static final class Context {
        final PreauthService preauth;
        final boolean proxyProtocol;
        /** 内嵌会合点的回环端口；0 表示不启用，TLS 分叉整条路径都不生效。 */
        final int rendezvousPort;
        final ExecutorService worker;

        Context(PreauthService preauth, boolean proxyProtocol, int rendezvousPort,
                ExecutorService worker) {
            this.preauth = preauth;
            this.proxyProtocol = proxyProtocol;
            this.rendezvousPort = rendezvousPort;
            this.worker = worker;
        }
    }

    private static volatile Context active;

    /**
     * 在 FMLServerStartedEvent 后调用（此时监听端点已绑定完毕）。
     *
     * @param preauth        预认证服务；null 表示不接受预认证帧
     * @param proxyProtocol  是否剥 PROXY 头
     * @param rendezvousPort 内嵌会合点的回环端口；0 表示不启用
     */
    static void install(MinecraftServer server, PreauthService preauth, boolean proxyProtocol,
                        int rendezvousPort) {
        if (preauth == null && !proxyProtocol && rendezvousPort <= 0) {
            return;
        }
        ExecutorService worker = Executors.newFixedThreadPool(MAX_CONCURRENT_PREAUTH,
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "netherway-preauth");
                        t.setDaemon(true);
                        return t;
                    }
                });
        final Context ctx = new Context(preauth, proxyProtocol, rendezvousPort, worker);
        try {
            NetworkSystem system = server.func_147137_ag();
            List<?> endpoints = ReflectionHelper.getPrivateValue(
                    NetworkSystem.class, system, "endpoints", "field_151274_e");
            int hooked = 0;
            synchronized (endpoints) {
                for (Object o : endpoints) {
                    Channel ch = ((ChannelFuture) o).channel();
                    if (ch.pipeline().get(ACCEPTOR_NAME) == null) {
                        ch.pipeline().addFirst(ACCEPTOR_NAME, new Acceptor(ctx));
                        hooked++;
                    }
                }
            }
            if (hooked == 0) {
                worker.shutdownNow();
                LOG.warn("没有找到可挂载的监听端点，预认证与 PROXY 剥头均未生效");
                return;
            }
            active = ctx;
            if (preauth != null) {
                LOG.info("预认证已挂载到 {} 个监听端点：玩家可在进服前于 MC 端口上换取直连凭证"
                        + "（不另开监听端口）", hooked);
            }
            if (proxyProtocol) {
                LOG.info("PROXY protocol 剥头已挂载到 {} 个监听端点，"
                        + "经隧道进来的连接将以真实来源地址示人", hooked);
            }
        } catch (Exception e) {
            worker.shutdownNow();
            LOG.warn("嗅探器挂载失败（MC 内部结构与预期不符？）。"
                    + "serve 侧若开着 -proxy-protocol 请先关掉，否则玩家会连不上", e);
        }
    }

    /** 服务端停止时收工。监听端点本身会被 MC 关掉，这里只管自己的线程池。 */
    static void shutdown() {
        Context ctx = active;
        active = null;
        if (ctx != null) {
            ctx.worker.shutdownNow();
        }
    }

    /** 拦在 ServerBootstrapAcceptor 前面，给每个新 accept 的连接装嗅探 handler。 */
    private static final class Acceptor extends ChannelInboundHandlerAdapter {

        private final Context ctx;

        Acceptor(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void channelRead(ChannelHandlerContext c, Object msg) {
            if (msg instanceof Channel) {
                ((Channel) msg).pipeline().addFirst(HANDLER_NAME, new Sniffer(ctx));
            }
            c.fireChannelRead(msg);
        }
    }

    /** 嗅探结果。 */
    private enum Mode {
        /** 还没攒够字节下定论。 */
        UNDECIDED,
        /** 是预认证帧，本连接由我们独占，永远不会交给 MC（进入时下游 handler 已全部摘掉）。 */
        PREAUTH,
        /** 是 frp 控制通道，字节原样转发给内嵌会合点（同样已独占）。 */
        RELAY,
    }

    /**
     * 连接最初的字节流过这里。
     *
     * <p>刻意不用 ByteToMessageDecoder：1.7.10 的 Netty 4.0.x 太老，
     * 「decode 中途移除自己」的边角行为在后续版本里修过不止一次，
     * 手动攒缓冲反而没有历史包袱。两个解析器都保证有界（预认证帧头 8 字节 +
     * payload 至多 4 KB，PROXY v1 至多 107 字节、v2 至多 16+4096），
     * 缓冲不会被撑爆。
     */
    private static final class Sniffer extends ChannelInboundHandlerAdapter {

        private final Context ctx;
        private ByteBuf pending;
        private Mode mode = Mode.UNDECIDED;
        /** RELAY 模式下通往内嵌会合点的连接；未接上前为 null。 */
        private Channel upstream;
        /** 有请求正在工作线程上处理，此时不再解析新帧。 */
        private boolean busy;
        private java.util.concurrent.ScheduledFuture<?> deadline;

        Sniffer(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void channelRead(ChannelHandlerContext c, Object msg) {
            if (!(msg instanceof ByteBuf)) {
                c.fireChannelRead(msg);
                return;
            }
            ByteBuf in = (ByteBuf) msg;

            // 中继模式下字节量不设上限（它承载的是整条 frp 控制通道），
            // 背压交给对端可写性，见 pumpToRendezvous
            if (mode == Mode.RELAY && upstream != null) {
                pumpToRendezvous(c, in);
                return;
            }
            if (pending == null) {
                pending = c.alloc().buffer(256);
            }
            // 独占连接后也要挡住无限投喂：一帧至多 8+4096，攒过头就是异常流量。
            // RELAY 模式另算：此刻正在连会合点，攒的是即将原样转发的字节，
            // 用预认证的帧长上限去卡它会把正常连接误杀。
            int cap = mode == Mode.RELAY ? RELAY_PENDING_MAX
                    : PreauthProtocol.REQUEST_HEADER_LEN + PreauthProtocol.MAX_PAYLOAD
                            + ProxyProtocolLimits.MAX;
            if (pending.readableBytes() + in.readableBytes() > cap) {
                in.release();
                releasePending();
                c.close();
                return;
            }
            pending.writeBytes(in);
            in.release();

            if (mode == Mode.UNDECIDED) {
                decide(c);
            } else if (mode == Mode.PREAUTH) {
                pump(c);
            }
            // mode == RELAY 但 upstream 尚未接上：接管是异步的，
            // 这期间到达的字节继续攒在 pending 里，等连上会合点一并送出
        }

        /** 攒够字节后判定这条连接归谁。 */
        private void decide(ChannelHandlerContext c) {
            byte[] peek = peek();
            Boolean nw = ctx.preauth == null ? Boolean.FALSE
                    : PreauthProtocol.looksLikeFrame(peek, peek.length);
            if (nw == null) {
                return; // 还不能确定，继续攒
            }
            if (Boolean.TRUE.equals(nw)) {
                mode = Mode.PREAUTH;
                takeover(c);
                armDeadline(c);
                pump(c);
                return;
            }
            // frp 的控制通道：TLS ClientHello 打头，转发给内嵌会合点。
            // 排在 PROXY 剥头之前——两者首字节不冲突（0x16 vs 'P'/0x0D），
            // 但先判它可以让不开会合点的部署完全不受影响。
            if (ctx.rendezvousPort > 0) {
                Boolean tls = TlsRecord.looksLikeHandshake(peek, peek.length);
                if (tls == null) {
                    return; // 还不能确定，继续攒
                }
                if (Boolean.TRUE.equals(tls)) {
                    mode = Mode.RELAY;
                    takeover(c);
                    connectRendezvous(c);
                    return;
                }
            }
            // 不是预认证帧也不是控制通道：交给 PROXY 剥头逻辑，或直接放行给 MC
            handleProxyProtocol(c);
        }

        // ---------- 转发给内嵌会合点 ----------

        /**
         * 接上内嵌会合点，把这条连接变成纯字节管道。
         *
         * <p>拨号复用本连接的事件循环：两端在同一个线程上，
         * upstream / pending 这些字段就不需要任何同步。
         */
        private void connectRendezvous(final ChannelHandlerContext c) {
            final Channel front = c.channel();
            // 先停读：拨号是异步的，这期间不再往 pending 里堆字节
            front.config().setAutoRead(false);

            Bootstrap b = new Bootstrap();
            b.group(front.eventLoop())
                    .channel(NioSocketChannel.class)
                    .handler(new RendezvousHandler(front));
            b.connect("127.0.0.1", ctx.rendezvousPort).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture f) {
                    if (!f.isSuccess()) {
                        LOG.warn("连接内嵌会合点 127.0.0.1:{} 失败，玩家这条隧道建不起来"
                                + "（内置 serve 没起来？）: {}", ctx.rendezvousPort,
                                String.valueOf(f.cause()));
                        releasePending();
                        front.close();
                        return;
                    }
                    upstream = f.channel();
                    ByteBuf head = pending;
                    pending = null;
                    if (head != null && head.isReadable()) {
                        // 嗅探期间吃掉的字节必须原样补回去，否则 frp 的握手就断了头
                        upstream.writeAndFlush(head);
                    } else if (head != null) {
                        head.release();
                    }
                    front.config().setAutoRead(true);
                }
            });
        }

        /**
         * 把玩家发来的字节泵给会合点。
         *
         * <p>背压刻意用对端的可写性驱动，而不是「写完成回调里再 read()」：
         * 后者是 Netty 官方 HexDumpProxy 的写法，但在 1.7.10 的 Netty 4.0.10
         * 上会死锁——回环上的写常常同步完成，那个 read() 正好落在读循环内部，
         * 被循环结尾的 removeReadOp 吞掉。实测几百 KB 即卡死。
         */
        private void pumpToRendezvous(final ChannelHandlerContext c, ByteBuf in) {
            if (!upstream.isActive()) {
                in.release();
                c.close();
                return;
            }
            upstream.writeAndFlush(in);
            if (!upstream.isWritable()) {
                c.channel().config().setAutoRead(false);
            }
        }

        /** 玩家侧重新可写 => 会合点侧可以继续读。 */
        @Override
        public void channelWritabilityChanged(ChannelHandlerContext c) {
            if (upstream != null && upstream.isActive() && c.channel().isWritable()) {
                upstream.config().setAutoRead(true);
            }
            c.fireChannelWritabilityChanged();
        }

        // ---------- 预认证 ----------

        /**
         * 独占连接：把 pipeline 里本 handler 之后的所有 handler 摘掉。这不只是
         * 清理——MC 的 ReadTimeoutHandler 就挂在后面，独占后我们不再
         * fireChannelRead，它收不到读事件就永远不会重置计时，会抢在
         * {@link #EXCHANGE_TIMEOUT_SECONDS} 之前掐断连接（30 秒，
         * FMLNetworkHandler.READ_TIMEOUT）。摘除顺带把 ReadTimeoutHandler
         * 的计时器也取消了（其 handlerRemoved 会 destroy）。
         *
         * <p>从尾部逐个摘而不按固定名字列表摘：其它 mod 可能往 pipeline
         * 里加了自己的 handler。挂在本 handler 之前的一律不动，它们还在
         * 给我们喂字节。
         */
        private void takeover(ChannelHandlerContext c) {
            ChannelPipeline p = c.pipeline();
            for (ChannelHandler last = p.last(); last != null && last != this; last = p.last()) {
                p.remove(last);
            }
        }

        /** 连接超时就断，半开的预认证连接不能在 MC 端口上堆积。 */
        private void armDeadline(final ChannelHandlerContext c) {
            deadline = c.executor().schedule(new Runnable() {
                @Override
                public void run() {
                    if (c.channel().isActive()) {
                        c.close();
                    }
                }
            }, EXCHANGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        /** 有完整帧就派发一个；一次只处理一个，处理期间不再解析。 */
        private void pump(ChannelHandlerContext c) {
            if (busy || pending == null) {
                return;
            }
            byte[] buf = peek();
            PreauthProtocol.Request req;
            try {
                req = PreauthProtocol.readRequest(buf, buf.length);
            } catch (IOException bad) {
                LOG.debug("预认证帧非法，断开: {}", bad.getMessage());
                releasePending();
                c.close();
                return;
            }
            if (req == null) {
                return; // 帧还没收全
            }
            pending.skipBytes(req.frameLength());
            busy = true;
            dispatch(c, req);
        }

        /** 处理放到工作线程，避免阻塞事件循环。 */
        private void dispatch(final ChannelHandlerContext c, final PreauthProtocol.Request req) {
            try {
                ctx.worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        byte[] reply = process(req);
                        respond(c, reply);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
                c.close();
            }
        }

        /** 在工作线程上跑，返回要写回的响应帧。 */
        private byte[] process(PreauthProtocol.Request req) {
            if (req.version != PreauthProtocol.VERSION) {
                return PreauthProtocol.errorResponse(
                        "协议版本不符（服务端 " + PreauthProtocol.VERSION + "）");
            }
            try {
                if (req.op == PreauthProtocol.OP_REQUEST) {
                    String[] id = PreauthProtocol.decodeIdentity(req.payload);
                    return ctx.preauth.handleRequest(id[0], id[1]).encode();
                }
                return PreauthProtocol.errorResponse("未知操作 " + req.op);
            } catch (IOException malformed) {
                return PreauthProtocol.errorResponse("请求内容无法解析");
            } catch (RuntimeException e) {
                LOG.warn("预认证处理异常", e);
                return PreauthProtocol.errorResponse("服务端内部错误");
            }
        }

        /** 把响应写回连接。单步请求-响应，写完即关。 */
        private void respond(final ChannelHandlerContext c, final byte[] reply) {
            if (!c.channel().isActive()) {
                return;
            }
            c.writeAndFlush(c.alloc().buffer(reply.length).writeBytes(reply))
                    .addListener(ChannelFutureListener.CLOSE);
        }

        // ---------- PROXY protocol ----------

        private void handleProxyProtocol(ChannelHandlerContext c) {
            SocketAddress remote = c.channel().remoteAddress();
            boolean loopback = remote instanceof InetSocketAddress
                    && ((InetSocketAddress) remote).getAddress() != null
                    && ((InetSocketAddress) remote).getAddress().isLoopbackAddress();
            if (!ctx.proxyProtocol || !loopback) {
                // 信任边界：非回环来源不剥头，原样放行并退出链路
                passRemaining(c);
                return;
            }
            byte[] peek = peek();
            cn.ripplecraft.netherway.core.ProxyProtocol.Result r =
                    cn.ripplecraft.netherway.core.ProxyProtocol.parse(peek);
            switch (r.status) {
                case NEED_MORE:
                    return;
                case NOT_PRESENT:
                    passRemaining(c);
                    return;
                case INVALID:
                    // 前缀像头但内容坏了。真 MC 客户端不会发这种字节，
                    // 多半是 serve 侧与本侧版本/配置岔了，断开最诚实。
                    LOG.warn("来自 {} 的连接带着非法的 PROXY 头（{}），已断开", remote, r.error);
                    releasePending();
                    c.close();
                    return;
                case PRESENT:
                default:
                    pending.skipBytes(r.headerLength);
                    if (r.source != null) {
                        rewriteRemote(c, r.source);
                    }
                    passRemaining(c);
            }
        }

        /**
         * 把真实来源写回 NetworkManager（1.7.10 里它就是 packet_handler）。
         * 原版在 channelActive 里已把 socketAddress 设成了回环地址，这里发生
         * 在首个字节之后，必然晚于它——登录日志与封禁看到的就是改写后的值。
         */
        private void rewriteRemote(ChannelHandlerContext c, InetSocketAddress source) {
            ChannelHandler tail = c.pipeline().get("packet_handler");
            if (tail instanceof NetworkManager) {
                ReflectionHelper.setPrivateValue(NetworkManager.class, (NetworkManager) tail,
                        source, "socketAddress", "field_150743_l");
                LOG.debug("PROXY 头已剥离，连接真实来源 {}", source);
            } else {
                LOG.warn("pipeline 里没有 packet_handler，真实来源 {} 未能写回（仅剥头）", source);
            }
        }

        // ---------- 公共 ----------

        private byte[] peek() {
            byte[] out = new byte[pending.readableBytes()];
            pending.getBytes(pending.readerIndex(), out);
            return out;
        }

        /** 把攒下的剩余字节交还 pipeline，并把自己摘掉。 */
        private void passRemaining(ChannelHandlerContext c) {
            ByteBuf rest = pending;
            pending = null;
            c.pipeline().remove(this);
            if (rest.isReadable()) {
                c.fireChannelRead(rest);
            } else {
                rest.release();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext c) {
            cancelDeadline();
            releasePending();
            closeUpstream();
            c.fireChannelInactive();
        }

        private void closeUpstream() {
            Channel up = upstream;
            upstream = null;
            if (up != null && up.isActive()) {
                up.close();
            }
        }

        /** 通往会合点的那一半。与前半程对称：可写性驱动背压，一端断另一端跟着断。 */
        private final class RendezvousHandler extends ChannelInboundHandlerAdapter {

            private final Channel front;

            RendezvousHandler(Channel front) {
                this.front = front;
            }

            @Override
            public void channelRead(ChannelHandlerContext c, Object msg) {
                front.writeAndFlush(msg);
                if (!front.isWritable()) {
                    c.channel().config().setAutoRead(false);
                }
            }

            /** 会合点侧重新可写 => 玩家侧可以继续读。 */
            @Override
            public void channelWritabilityChanged(ChannelHandlerContext c) {
                if (front.isActive() && c.channel().isWritable()) {
                    front.config().setAutoRead(true);
                }
            }

            @Override
            public void channelInactive(ChannelHandlerContext c) {
                if (front.isActive()) {
                    front.close();
                }
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext c, Throwable cause) {
                LOG.debug("与内嵌会合点之间的连接异常: {}", String.valueOf(cause));
                c.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext c, Throwable cause) {
            if (mode == Mode.PREAUTH || mode == Mode.RELAY) {
                // 独占后下游已无人消化 IO 异常（原本由 NetworkManager 收场），
                // 不自己关连接就会冒到 pipeline 尾部刷 netty 警告
                LOG.debug("独占中的连接异常（{}），断开: {}", mode, cause.toString());
                c.close();
                return;
            }
            c.fireExceptionCaught(cause);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext c) {
            cancelDeadline();
            releasePending();
            closeUpstream();
        }

        private void cancelDeadline() {
            if (deadline != null) {
                deadline.cancel(false);
                deadline = null;
            }
        }

        private void releasePending() {
            if (pending != null) {
                pending.release();
                pending = null;
            }
        }
    }

    /** PROXY 头解析在下定论前需要缓冲的上限，与解析器的规格一致。 */
    private static final class ProxyProtocolLimits {
        static final int MAX = 16 + cn.ripplecraft.netherway.core.ProxyProtocol.V2_MAX_PAYLOAD;

        private ProxyProtocolLimits() {
        }
    }
}
