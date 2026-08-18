package cn.ripplecraft.netherway.bukkit;

import cn.ripplecraft.netherway.modern.SnifferCore;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;

/**
 * Hooks the server's listen channel(s) so every accepted connection gets the
 * Netherway sniffer first in its pipeline — the same job the mod does with
 * {@code ServerConnectionInitMixin}, done without Mixin.
 *
 * <p>How: {@code CraftServer.getServer()} yields the NMS server object; the
 * connection listener is found as the field whose class declares a
 * {@code List<ChannelFuture>} (names differ across versions and mappings, the
 * shape does not); each future in that list is one bound listen endpoint. An
 * acceptor handler added first on the (parent) listen channel sees every
 * accepted child channel before Netty's {@code ServerBootstrapAcceptor} does,
 * and registers a {@link ChannelInitializer} that therefore runs ahead of
 * Minecraft's own: {@link SnifferCore#attach} puts the sniffer at the head of
 * a nearly empty pipeline and Minecraft's handlers line up behind it. This is
 * the same injection strategy ViaVersion has used on Bukkit for years.
 *
 * <p>Everything is located by shape (generic signature, falling back to
 * runtime list contents) rather than by name, so one build covers Spigot's
 * obfuscated runtime and Paper's Mojang-mapped (1.20.5+) runtime alike.
 */
final class PipelineInjector {

    static final String HANDLER_NAME = "netherway_injector";

    private final List<Channel> hooked = new ArrayList<Channel>();
    private String lastError = "listen channel not bound yet";

    /**
     * Idempotent; safe to call again after a failed attempt.
     *
     * @return total number of listen channels currently hooked (0 = failed)
     */
    int install() {
        try {
            List<?> futures = listenChannels();
            if (futures == null) {
                return 0;
            }
            synchronized (futures) {
                if (futures.isEmpty()) {
                    lastError = "listen channel not bound yet";
                    return 0;
                }
                for (Object f : futures) {
                    Channel ch = ((ChannelFuture) f).channel();
                    if (ch.pipeline().get(HANDLER_NAME) == null) {
                        ch.pipeline().addFirst(HANDLER_NAME, new Acceptor());
                        hooked.add(ch);
                    }
                }
            }
            return hooked.size();
        } catch (ReflectiveOperationException e) {
            lastError = e.toString();
            return 0;
        } catch (RuntimeException e) {
            lastError = e.toString();
            return 0;
        }
    }

    /**
     * Detaches from the listen channels. Sniffers already attached to live
     * connections stay and die with their connection; new connections are no
     * longer touched.
     */
    void uninstall() {
        for (Channel ch : hooked) {
            try {
                if (ch.pipeline().get(HANDLER_NAME) != null) {
                    ch.pipeline().remove(HANDLER_NAME);
                }
            } catch (RuntimeException ignored) {
                // channel already closed
            }
        }
        hooked.clear();
    }

    /** Human-readable reason of the last failed {@link #install}. */
    String lastError() {
        return lastError;
    }

    // ---------- reflective discovery ----------

    /** The NMS server's {@code List<ChannelFuture>} of bound endpoints, or null. */
    private List<?> listenChannels() throws ReflectiveOperationException {
        Object craft = Bukkit.getServer();
        Object mcServer = craft.getClass().getMethod("getServer").invoke(craft);

        // Pass 1: identify the connection listener by generic signature.
        // Works even before the port is bound (empty list).
        for (Field holder : instanceFields(mcServer.getClass())) {
            Field listField = channelFutureListField(holder.getType());
            if (listField == null) {
                continue;
            }
            holder.setAccessible(true);
            Object listener = holder.get(mcServer);
            if (listener == null) {
                lastError = "connection listener not constructed yet";
                return null;
            }
            listField.setAccessible(true);
            return (List<?>) listField.get(listener);
        }

        // Pass 2: identify by runtime contents, in case the runtime jar lost
        // generic signatures. Only conclusive after bind — fine, the caller
        // retries on the first tick.
        for (Field holder : instanceFields(mcServer.getClass())) {
            holder.setAccessible(true);
            Object value = holder.get(mcServer);
            if (value == null) {
                continue;
            }
            for (Field f : instanceFields(value.getClass())) {
                if (!List.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object list = f.get(value);
                if (list instanceof List) {
                    synchronized (list) {
                        List<?> l = (List<?>) list;
                        if (!l.isEmpty() && l.get(0) instanceof ChannelFuture) {
                            return l;
                        }
                    }
                }
            }
        }
        lastError = "connection listener not found on " + mcServer.getClass().getName();
        return null;
    }

    /** Declared instance fields of the class hierarchy, JDK/library types skipped. */
    private static List<Field> instanceFields(Class<?> type) {
        List<Field> out = new ArrayList<Field>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()
                        || f.getType().isArray()) {
                    continue;
                }
                String owner = f.getType().getName();
                if (owner.startsWith("java.") || owner.startsWith("javax.")
                        || owner.startsWith("io.netty.") || owner.startsWith("com.google.")
                        || owner.startsWith("org.apache.")) {
                    // keep java.util.List holders: pass 2 inspects List fields directly
                    if (!List.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                }
                out.add(f);
            }
        }
        return out;
    }

    /** A declared {@code List<ChannelFuture>} field of the class hierarchy, or null. */
    private static Field channelFutureListField(Class<?> owner) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())
                        || !List.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                Type g = f.getGenericType();
                if (!(g instanceof ParameterizedType)) {
                    continue;
                }
                Type[] args = ((ParameterizedType) g).getActualTypeArguments();
                if (args.length == 1 && args[0] == ChannelFuture.class) {
                    return f;
                }
            }
        }
        return null;
    }

    // ---------- per-connection hookup ----------

    /**
     * Sits first on the listen (parent) channel; every accepted child channel
     * passes through as the message. One instance per parent channel — never
     * shared, so no {@code @Sharable} needed.
     */
    private static final class Acceptor extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Channel) {
                ((Channel) msg).pipeline().addLast(new SnifferInitializer());
            }
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Added to the child before Minecraft's own initializer, so it runs first
     * on registration: the sniffer lands at the head of the pipeline and
     * Minecraft's handlers are appended behind it — same final order as the
     * mod's Mixin (sniffer ahead of timeout/legacy_query/splitter).
     */
    private static final class SnifferInitializer extends ChannelInitializer<Channel> {

        @Override
        protected void initChannel(Channel ch) {
            SnifferCore.attach(ch);
        }
    }
}
