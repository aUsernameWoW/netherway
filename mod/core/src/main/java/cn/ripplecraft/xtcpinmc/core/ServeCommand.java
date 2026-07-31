package cn.ripplecraft.xtcpinmc.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组装宿主侧 serve 进程的命令行（服务端 mod 内置启动 agent 用）。
 *
 * <p>serve 是 frp 专用的独立工具、不走 backend 抽象，所以这里的键名映射
 * 是 frp-xtcp 专属的：把服务端配置里的参数表翻译成 serve 的旗标。
 * 关键在于参数表与下发给客户端的凭证**同源**——凭证和代理注册永远一致，
 * 不会出现「客户端拿着 test 房间的凭证，宿主机却注册着 gtnh-p2p」的漂移。
 */
public final class ServeCommand {

    private ServeCommand() {
    }

    /**
     * 由 frp-xtcp 参数表组装 serve 命令行。
     *
     * <p>与 backend 契约同一纪律：无法识别的键直接忽略；缺失的键不传旗标，
     * 留给 agent 用构建期注入的默认值补齐。
     *
     * @param localPort Minecraft 服务器监听的本地端口
     */
    public static List<String> build(Path exe, Map<String, String> params, int localPort) {
        return build(exe, params, localPort, null);
    }

    /**
     * 同上，另携带向 frps 侧 authplugin 表明身份的静态令牌
     * （对应 serve 的 {@code -meta-token}）；null 或空表示不带。
     */
    public static List<String> build(Path exe, Map<String, String> params, int localPort,
                                     String metaToken) {
        return build(exe, params, localPort, metaToken, null);
    }

    /**
     * 同上，另指定 PROXY protocol 版本（"v1"/"v2"，对应 serve 的
     * {@code -proxy-protocol}）；null 或空表示不开。开了就意味着 MC 服务端
     * 必须装着剥头组件（本 mod 的平台层负责），值与配置键
     * {@code server.proxyProtocol} 同源。
     */
    public static List<String> build(Path exe, Map<String, String> params, int localPort,
                                     String metaToken, String proxyProtocol) {
        // 键名与 Go 侧 internal/backend/frpxtcp 的常量一致（见 frpXtcpParamKeys）
        Map<String, String> flagOf = new LinkedHashMap<String, String>();
        flagOf.put("server", "-server");
        flagOf.put("serverPort", "-server-port");
        flagOf.put("token", "-token");
        flagOf.put("stun", "-stun");
        flagOf.put(Credentials.PARAM_ROOM, "-room");
        flagOf.put("secret", "-secret");

        List<String> cmd = new ArrayList<String>();
        cmd.add(exe.toAbsolutePath().toString());
        cmd.add("serve");
        for (Map.Entry<String, String> e : flagOf.entrySet()) {
            String v = params.get(e.getKey());
            if (v != null && !v.isEmpty()) {
                cmd.add(e.getValue());
                cmd.add(v);
            }
        }
        cmd.add("-port");
        cmd.add(Integer.toString(localPort));
        if (metaToken != null && !metaToken.isEmpty()) {
            cmd.add("-meta-token");
            cmd.add(metaToken);
        }
        if (proxyProtocol != null && !proxyProtocol.isEmpty()) {
            cmd.add("-proxy-protocol");
            cmd.add(proxyProtocol);
        }
        return cmd;
    }

    /**
     * 供日志输出的命令行描述。serve 的旗标语义已知，只需抹掉真正敏感的
     * {@code -token} 与 {@code -secret}；服务器地址与房间名保留——排查
     * 「注册到哪去了」正需要它们。
     */
    public static String describe(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            String arg = cmd.get(i);
            sb.append(arg);
            if (("-token".equals(arg) || "-secret".equals(arg) || "-meta-token".equals(arg))
                    && i + 1 < cmd.size()) {
                sb.append(" ***");
                i++;
            }
        }
        return sb.toString();
    }
}
