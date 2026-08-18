package cn.ripplecraft.netherway.modern;

/**
 * modern（1.16.5+）平台层的公共常量与身份。各版本 Forge/Fabric 入口共用。
 */
public final class NetherwayModern {

    public static final String MODID = "netherway";

    /**
     * 自定义频道的 namespace 与 path。1.13 起频道名必须是 ResourceLocation
     * （小写、带 namespace），与 1.7.10/1.12.2 的裸 {@code "netherway"} 不同——
     * 跨版本客户端与服务端本就不能互联，两代频道名不需要互通；但 Bukkit/Sponge
     * 插件在 1.13+ 侧要注册 {@code netherway:main}。载荷仍是 core 的裸字节。
     */
    public static final String CHANNEL_NAMESPACE = "netherway";
    public static final String CHANNEL_PATH = "main";

    /** 供日志与遥测使用；实际 MC 版本由各版本入口在装配时传入。 */
    private NetherwayModern() {
    }
}
