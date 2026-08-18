package cn.ripplecraft.netherway.modern.client;

/**
 * 静态桥，让 ConnectScreen / ServerStatusPinger 的 Mixin 能查运行期路由表
 * ——Mixin 类无法拿到注入的实例，只能走静态入口。ClientRuntime 初始化时
 * 登记路由表；未登记（客户端功能关闭或未预热）时 resolve 恒返回 null，
 * Mixin 全部走原版分支。
 */
public final class ClientRouting {

    private static volatile WarmupEntryRouter router;

    private ClientRouting() {
    }

    static void install(WarmupEntryRouter r) {
        router = r;
    }

    /** 点击/探测该持久入口时应改用的回环地址（{@code 127.0.0.1:port}），无则 null。 */
    public static String resolve(String serverAddress) {
        WarmupEntryRouter r = router;
        if (r == null) {
            return null;
        }
        WarmupEntryRouter.Route route = r.resolve(serverAddress);
        return route == null ? null : route.localAddress();
    }

    /** 覆盖模式是否开启；关闭时 Mixin 直接放行、连解析都不必做。 */
    public static boolean active() {
        WarmupEntryRouter r = router;
        return r != null && r.replacesEntries();
    }
}
