package cn.ripplecraft.netherway.modern;

import cn.ripplecraft.netherway.modern.mixin.ConnectionAddressAccessor;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;

/**
 * Mod-side {@link SnifferCore.AddressRewriter}: writes the PROXY-stripped
 * source address into the {@code Connection} handler (named
 * {@code packet_handler} in the pipeline). Field access goes through a Mixin
 * accessor so the mojmap/SRG/intermediary runtime names are all handled by
 * Mixin — do not fall back to reflection by name here.
 *
 * <p>This is the only class besides the mixins that references a Minecraft
 * type on the server half; platforms without Mixin (the Bukkit plugin)
 * exclude it and supply their own rewriter.
 */
public final class ConnectionAddressRewriter implements SnifferCore.AddressRewriter {

    @Override
    public boolean apply(Channel channel, InetSocketAddress source) {
        ChannelHandler tail = channel.pipeline().get("packet_handler");
        if (tail instanceof Connection) {
            ((ConnectionAddressAccessor) tail).netherway$setAddress(source);
            return true;
        }
        return false;
    }
}
