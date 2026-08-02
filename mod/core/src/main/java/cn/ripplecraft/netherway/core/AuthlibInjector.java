package cn.ripplecraft.netherway.core;

import java.util.List;
import java.util.Locale;

/**
 * 从 JVM 启动参数里读出 authlib-injector 挂载的皮肤站 API root。
 *
 * <p>客户端与服务端都用得上：服务端拿它去查 hasJoined，客户端拿它决定
 * 「我的 accessToken 该发给谁」。两边本来就都得挂 authlib-injector 才能用
 * 第三方皮肤站，地址已经在命令行上，不必让服主和玩家再抄一遍。
 *
 * <p>{@code java.lang.management} 是公共稳定 API（不是 JDK 内部），
 * Java 8–25 都在；但极简 jlink 运行时里可能整个模块都没有，
 * 所以取不到时安静地返回空串。
 */
public final class AuthlibInjector {

    private AuthlibInjector() {
    }

    /** 本进程挂着的皮肤站 API root；没挂或读不到返回空串。 */
    public static String detect() {
        try {
            List<String> args = java.lang.management.ManagementFactory
                    .getRuntimeMXBean().getInputArguments();
            for (String arg : args) {
                String url = parseAgentArg(arg);
                if (url != null) {
                    return url;
                }
            }
        } catch (Throwable unavailable) {
            // java.management 不可用：当作没挂
        }
        return "";
    }

    /**
     * 从 {@code -javaagent:<path>authlib-injector<...>.jar=<API root>} 里取出 API root。
     * 不是这个形状返回 null。
     */
    static String parseAgentArg(String jvmArg) {
        if (jvmArg == null || !jvmArg.startsWith("-javaagent:")) {
            return null;
        }
        int eq = jvmArg.indexOf('=');
        if (eq < 0 || eq + 1 >= jvmArg.length()) {
            return null;
        }
        String jar = jvmArg.substring("-javaagent:".length(), eq);
        if (!jar.toLowerCase(Locale.ROOT).contains("authlib-injector")) {
            return null;
        }
        String url = jvmArg.substring(eq + 1).trim();
        return url.isEmpty() ? null : url;
    }
}
