package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.TokenIssuer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import java.util.Map;
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

    private static final Logger LOG = LogManager.getLogger(Netherway.MODID);

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
                LOG.warn(L10n.tr("fserver.incompleteCredKeys"));
            }
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) event.player;
        cred = withPlayerToken(cred, player);
        channel.sendTo(new FMLProxyPacket(new net.minecraft.network.PacketBuffer(
                Unpooled.wrappedBuffer(cred.encode())), Netherway.CHANNEL), player);
        // Credentials.toString 刻意只列参数键名，不含 token 与密钥值
        LOG.info(L10n.tr("fserver.delivered", player.getName(), cred));
    }

    /**
     * 启用令牌签发时，为该玩家附加绑定其 UUID、带有效期的身份参数。
     * 每次登录都重新签发——客户端会覆盖缓存，等于自动续签。
     */
    private Credentials withPlayerToken(Credentials cred, EntityPlayerMP player) {
        String key = config.tokenSigningKey();
        if (key.isEmpty()) {
            return cred;
        }
        String uuid = player.getUniqueID().toString();
        long expiry = System.currentTimeMillis() / 1000L
                + config.tokenTtlDays() * 86400L;
        Map<String, String> extra = new LinkedHashMap<String, String>();
        extra.put(Credentials.PARAM_USER, uuid);
        extra.put(Credentials.PARAM_USER_TOKEN, TokenIssuer.issue(key, uuid, expiry));
        return cred.withExtraParams(extra);
    }
}
