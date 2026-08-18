package cn.ripplecraft.netherway.bukkit;

import cn.ripplecraft.netherway.modern.SnifferCore;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * PROXY-protocol address write-back for Bukkit: finds the NMS connection
 * object (the {@code packet_handler} in the pipeline, mojmap
 * {@code Connection}) and sets its remote-address field to the stripped real
 * source. The mod does this with a Mixin accessor; here the field is located
 * reflectively so one build covers Spigot's obfuscated runtime and Paper's
 * Mojang-mapped (1.20.5+) runtime.
 *
 * <p>Lookup is by type with a value tiebreak, not by name: the class can hold
 * more than one {@link SocketAddress} field (Paper adds {@code virtualHost}),
 * but at sniff time — the connection's very first bytes, before any handshake
 * — the remote-address field is the only one already holding the channel's
 * {@code remoteAddress()}, set in {@code channelActive}. If that still fails
 * to single out a field, the rewriter gives up and the sniffer logs the
 * header as stripped-but-not-applied.
 */
final class NmsAddressRewriter implements SnifferCore.AddressRewriter {

    /** Resolved once; the connection class is the same for every connection. */
    private volatile Field addressField;
    private volatile boolean unusable;

    @Override
    public boolean apply(Channel channel, InetSocketAddress source) {
        ChannelHandler tail = channel.pipeline().get("packet_handler");
        if (tail == null || unusable) {
            return false;
        }
        try {
            Field f = fieldFor(tail, channel);
            if (f == null) {
                unusable = true;
                return false;
            }
            f.set(tail, source);
            return true;
        } catch (ReflectiveOperationException e) {
            unusable = true;
            return false;
        } catch (RuntimeException e) {
            unusable = true;
            return false;
        }
    }

    private Field fieldFor(Object connection, Channel channel)
            throws ReflectiveOperationException {
        Field cached = addressField;
        if (cached != null && cached.getDeclaringClass().isInstance(connection)) {
            return cached;
        }
        List<Field> candidates = new ArrayList<Field>();
        for (Class<?> c = connection.getClass(); c != null && c != Object.class;
                c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())
                        || !SocketAddress.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                candidates.add(f);
            }
        }
        Field resolved = null;
        if (candidates.size() == 1) {
            resolved = candidates.get(0);
        } else if (!candidates.isEmpty()) {
            SocketAddress remote = channel.remoteAddress();
            for (Field f : candidates) {
                if (remote != null && remote.equals(f.get(connection))) {
                    if (resolved != null) {
                        return null; // still ambiguous — refuse to guess
                    }
                    resolved = f;
                }
            }
        }
        if (resolved != null) {
            addressField = resolved;
        }
        return resolved;
    }
}
