package cn.ripplecraft.netherway.forge;

import cpw.mods.fml.common.network.FMLEventChannel;

/**
 * 专用服务器侧的空实现。
 *
 * <p>客户端逻辑全部在 {@link ClientProxy} 里接线——那边会 import
 * {@code net.minecraft.client} 的类，这个类绝不能碰它们，
 * 否则专用服务器上一加载就 NoClassDefFoundError。
 */
public class CommonProxy {

    public void initClient(FMLEventChannel channel, ModConfig config) {
        // 专用服务器没有客户端半边
    }
}
