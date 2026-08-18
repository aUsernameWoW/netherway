package cn.ripplecraft.netherway.modern;

import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.PreauthProtocol;
import cn.ripplecraft.netherway.core.PreauthService;
import cn.ripplecraft.netherway.core.TlsRecord;
import cn.ripplecraft.netherway.modern.mixin.ConnectionAddressAccessor;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.Connection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 首字节嗅探 handler（modern 版）：一个 handler 同时管预认证帧、frp 控制
 * 通道转发与 PROXY protocol 剥头三件事，语义与 forge-1.7.10 的
 * ConnectionSniffer 完全一致（三者抢同一批「连接最初的字节」，必须合一）。
 *
 * <p>与 1.7.10 版的两点差异：
 * <ul>
 * <li>安装方式：不再反射 endpoints 列表挂 Acceptor，而是由共享 Mixin
 *     （ServerConnectionListenerInitMixin，注入 TCP initializer 的
 *     initChannel 尾部）对每条 accept 的连接调 {@link #attach}。Context
 *     未激活时 attach 是空操作，语义等同 1.7.10 的「装上之前的连接不管」。</li>
 * <li>拨会合点的 channel 类不再写死 NioSocketChannel，改用前端连接自己的
 *     类：1.9+ 的服务端在 Linux 上默认启用 epoll 原生 transport
 *     （use-native-transport），Nio channel 注册不进 epoll 的事件循环。</li>
 * </ul>
 *
 * <p>信任边界不变：<b>PROXY 头只信回环</b>，<b>预认证帧接受任何来源</b>
 * （预下发不做身份验证，准入交给 MC 服务端自己的白名单与正版验证）。
 */
public final class SnifferCore {

    private static final Logger LOG = LogManager.getLogger(NetherwayModern.MODID);
    private static final String HANDLER_NAME = "netherway_sniffer";

    /** 一条预认证连接从建立到完成的宽限；独占后 MC 的 30 秒读超时已被摘除。 */
    private static final int EXCHANGE_TIMEOUT_SECONDS = 40;

    /** 同时在处理的预认证请求上限。 */
    private static final int MAX_CONCURRENT_PREAUTH = 8;

    /** RELAY 模式下、会合点还没接上时允许攒的字节上限（防拨号卡住时无界增长）。 */
    private static final int RELAY_PENDING_MAX = 64 * 1024;

    private SnifferCore() {
    }

    /** 嗅探器的运行期依赖，install 时组装一次。 */
    static final class Context {
        final PreauthService preauth;
        final boolean proxyProtocol;
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
     * 服务器就绪后调用（ServerStarted 事件）。之后 accept 的每条 TCP 连接
     * 都会经 {@link #attach} 得到嗅探 handler。
     */
    public static void install(PreauthService preauth, boolean proxyProtocol,
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
        active = new Context(preauth, proxyProtocol, rendezvousPort, worker);
        if (preauth != null) {
            LOG.info(L10n.tr("sniffer.preauthHooked", 1));
        }
        if (proxyProtocol) {
            LOG.info(L10n.tr("sniffer.proxyHooked", 1));
        }
    }

    /** 服务端停止时收工。监听端点本身由 MC 关闭，这里只管自己的线程池。 */
    public static void shutdown() {
        Context ctx = active;
        active = null;
        if (ctx != null) {
            ctx.worker.shutdownNow();
        }
    }

    /**
     * 由 Mixin 在每条 accept 连接的 initChannel 尾部调用。pipeline 此刻
     * 已含 MC 的全部 handler，addFirst 保证首字节先经我们再进
     * timeout/legacy_query/splitter。
     */
    public static void attach(Channel ch) {
        Context ctx = active;
        if (ctx == null || ch.pipeline().get(HANDLER_NAME) != null) {
            return;
        }
        ch.pipeline().addFirst(HANDLER_NAME, new Sniffer(ctx));
    }

    /** 嗅探结果。 */
    private enum Mode {
        UNDECIDED,
        /** 预认证帧：连接由我们独占，进入时下游 handler 已全部摘掉。 */
        PREAUTH,
        /** frp 控制通道：字节原样转发给内嵌会合点（同样已独占）。 */
        RELAY,
    }

    /**
     * 连接最初的字节流过这里。刻意不用 ByteToMessageDecoder、手动攒缓冲，
     * 与 1.7.10 平台层保持同一写法（4.0/4.1 行为一致，无历史包袱）。
     * 两个解析器都保证有界，缓冲不会被撑爆。
     */
    private static final class Sniffer extends ChannelInboundHandlerAdapter {

        private final Context ctx;
        private ByteBuf pending;
        private Mode mode = Mode.UNDECIDED;
        private Channel upstream;
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

            if (mode == Mode.RELAY && upstream != null) {
                pumpToRendezvous(c, in);
                return;
            }
            if (pending == null) {
                pending = c.alloc().buffer(256);
            }
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
                return;
            }
            if (Boolean.TRUE.equals(nw)) {
                mode = Mode.PREAUTH;
                takeover(c);
                armDeadline(c);
                pump(c);
                return;
            }
            // frp 控制通道：TLS ClientHello 打头，转发给内嵌会合点。
            if (ctx.rendezvousPort > 0) {
                Boolean tls = TlsRecord.looksLikeHandshake(peek, peek.length);
                if (tls == null) {
                    return;
                }
                if (Boolean.TRUE.equals(tls)) {
                    mode = Mode.RELAY;
                    takeover(c);
                    connectRendezvous(c);
                    return;
                }
            }
            handleProxyProtocol(c);
        }

        // ---------- 转发给内嵌会合点 ----------

        /** 接上内嵌会合点，把这条连接变成纯字节管道。拨号复用本连接的事件循环。 */
        private void connectRendezvous(final ChannelHandlerContext c) {
            final Channel front = c.channel();
            front.config().setAutoRead(false);

            Bootstrap b = new Bootstrap();
            // channel 类型必须与前端连接同族：epoll 事件循环装不下 Nio channel。
            // accept 出来的 channel 自己的类恰好就是匹配的 SocketChannel 实现。
            b.group(front.eventLoop())
                    .channel(front.getClass())
                    .handler(new RendezvousHandler(front));
            b.connect("127.0.0.1", ctx.rendezvousPort).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture f) {
                    if (!f.isSuccess()) {
                        LOG.warn(L10n.tr("sniffer.rendezvousDialFailed",
                                ctx.rendezvousPort, f.cause()));
                        releasePending();
                        front.close();
                        return;
                    }
                    upstream = f.channel();
                    ByteBuf head = pending;
                    pending = null;
                    if (head != null && head.isReadable()) {
                        // 嗅探期间吃掉的字节必须原样补回去，否则 frp 的握手断头
                        upstream.writeAndFlush(head);
                    } else if (head != null) {
                        head.release();
                    }
                    front.config().setAutoRead(true);
                }
            });
        }

        /**
         * 把玩家发来的字节泵给会合点。背压刻意用对端可写性驱动而不是
         * 「写完成回调里再 read()」——后者在 1.7.10 的 Netty 4.0.10 上实测
         * 死锁；4.1 未必复现，但可写性驱动在两边都正确，各版本同一写法。
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
         * 独占连接：把 pipeline 里本 handler 之后的所有 handler 摘掉。
         * MC 的 ReadTimeoutHandler 就挂在后面，独占后不再 fireChannelRead，
         * 不摘的话 30 秒一到先被它掐断。从尾部逐个摘而不按固定名字列表摘：
         * 其它 mod 可能加了自己的 handler。
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
                LOG.debug(L10n.tr("sniffer.badPreauthFrame", bad.getMessage()));
                releasePending();
                c.close();
                return;
            }
            if (req == null) {
                return;
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
                        L10n.tr("sniffer.versionMismatch", PreauthProtocol.VERSION));
            }
            try {
                if (req.op == PreauthProtocol.OP_REQUEST) {
                    String[] id = PreauthProtocol.decodeIdentity(req.payload);
                    return ctx.preauth.handleRequest(id[0], id[1]).encode();
                }
                return PreauthProtocol.errorResponse(L10n.tr("sniffer.unknownOp", req.op));
            } catch (IOException malformed) {
                return PreauthProtocol.errorResponse(L10n.tr("sniffer.badRequest"));
            } catch (RuntimeException e) {
                LOG.warn(L10n.tr("sniffer.preauthError"), e);
                return PreauthProtocol.errorResponse(L10n.tr("sniffer.internalError"));
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
                    LOG.warn(L10n.tr("sniffer.badProxyHeader", remote, r.error));
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
         * 把真实来源写回 Connection（pipeline 里名为 packet_handler 的那个）。
         * 字段访问经 Mixin accessor，mojmap/SRG/intermediary 三套运行时名
         * 由 Mixin 自动处理——不要退回反射字符串。
         */
        private void rewriteRemote(ChannelHandlerContext c, InetSocketAddress source) {
            ChannelHandler tail = c.pipeline().get("packet_handler");
            if (tail instanceof Connection) {
                ((ConnectionAddressAccessor) tail).netherway$setAddress(source);
                LOG.debug(L10n.tr("sniffer.proxyStripped", source));
            } else {
                LOG.warn(L10n.tr("sniffer.noPacketHandler", source));
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
                LOG.debug(L10n.tr("sniffer.relayError", cause));
                c.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext c, Throwable cause) {
            if (mode == Mode.PREAUTH || mode == Mode.RELAY) {
                // 独占后下游已无人消化 IO 异常，不自己关就会刷 netty 警告
                LOG.debug(L10n.tr("sniffer.exclusiveError", mode, cause));
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
