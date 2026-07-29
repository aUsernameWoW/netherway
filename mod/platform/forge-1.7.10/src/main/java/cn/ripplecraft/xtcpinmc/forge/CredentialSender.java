package cn.ripplecraft.xtcpinmc.forge;

import cn.ripplecraft.xtcpinmc.core.Credentials;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.EntityPlayerMP;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 服务端半边：玩家登录后下发直连凭证。
 *
 * <p>这是整套设计的安全基础——凭证不随客户端分发，能走到
 * {@code PlayerLoggedInEvent} 的玩家必然已通过服务器既有的
 * 正版验证/白名单，所以不需要另建鉴权。
 *
 * <p>没装 mod 的客户端会直接忽略未知频道的包，多发无害；
 * 升级切换后玩家重新登录会再收到一次凭证，客户端按
 * {@link Credentials#dedupKey()} 去重。
 *
 * <p>这个类不能引用任何 {@code net.minecraft.client} 的类型，
 * 它会在专用服务器上加载。
 */
public final class CredentialSender {

    private static final Logger LOG = LogManager.getLogger(XtcpInMc.MODID);

    private final FMLEventChannel channel;
    private final ModConfig config;

    /** 配置不完整只提醒一次，别每个玩家登录都刷一遍日志。 */
    private boolean warnedInvalid;

    public CredentialSender(FMLEventChannel channel, ModConfig config) {
        this.channel = channel;
        this.config = config;
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!config.serverEnabled() || !(event.player instanceof EntityPlayerMP)) {
            return;
        }
        Credentials cred = config.serverCredentials();
        if (cred == null) {
            if (!warnedInvalid) {
                warnedInvalid = true;
                LOG.warn("server.enabled 已开启但凭证配置不完整，不会下发直连凭证"
                        + "（检查 server.params 里的 room 等键）");
            }
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) event.player;
        channel.sendTo(new FMLProxyPacket(
                Unpooled.wrappedBuffer(cred.encode()), XtcpInMc.CHANNEL), player);
        // Credentials.toString 刻意只列参数键名，不含 token 与密钥值
        LOG.info("已向 {} 下发直连凭证 {}", player.getCommandSenderName(), cred);
    }
}
