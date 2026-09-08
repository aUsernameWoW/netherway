package cn.ripplecraft.netherway.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 组装宿主侧 serve 进程的命令行（服务端 mod 内置启动 agent 用）。
 *
 * <p>serve 走通用的 {@code -backend} + {@code -O key=value}，参数表原样透传。
 * 关键在于参数表与下发给客户端的凭证<b>同源</b>——凭证和服务端发布的房间
 * 永远一致，不会出现「客户端拿着 test 房间的凭证，宿主机却发布着
 * production」的漂移。
 */
public final class ServeCommand {

    private ServeCommand() {
    }

    /**
     * Whether the built-in serve can publish the given backend. Single source
     * of truth for the platform launchers' gate; {@link #build} refuses the
     * same ids. Unknown ids must be refused up front: the agent would reject
     * them anyway, but only after the binary has been extracted and a process
     * spawned.
     */
    public static boolean supportsBackend(String backendId) {
        return Credentials.BACKEND_GONC_P2P.equals(backendId);
    }

    /** serve 的可选项。逐个加旗标参数会让签名膨胀，集中在这里。 */
    public static final class Options {

        String proxyProtocol;
        int rendezvousPort;

        /**
         * PROXY protocol version ("v1"/"v2", flag {@code -proxy-protocol});
         * same value as the cfg key {@code server.proxyProtocol}. Turning it
         * on means the MC side must strip the header (this mod's platform
         * layer does). serve injects the punched peer's address itself, so
         * the MC server sees the real player IP.
         */
        public Options proxyProtocol(String v) {
            this.proxyProtocol = v;
            return this;
        }

        /**
         * Loopback port of the embedded rendezvous ({@code -rendezvous}).
         * Non-zero makes serve embed the signaling broker on that loopback
         * port instead of using public brokers; players' signaling
         * connections are relayed in from the Minecraft port by the sniffer.
         * The platform layer picks the port and tells the sniffer the same
         * number; the two must agree.
         */
        public Options rendezvousPort(int v) {
            this.rendezvousPort = v;
            return this;
        }
    }

    /**
     * 按 backend 组装 serve 命令行：{@code -backend} + 参数表经 {@code -O}
     * 原样透传（空值跳过），再加本地端口与可选项。
     *
     * @param localPort Minecraft 服务器监听的本地端口
     * @throws IllegalArgumentException backend 不受内置 serve 支持
     *         （调用方应先经 {@link #supportsBackend} 把关）
     */
    public static List<String> build(Path exe, String backendId, Map<String, String> params,
                                     int localPort, Options opts) {
        if (!supportsBackend(backendId)) {
            throw new IllegalArgumentException(L10n.tr("serve.backendUnsupported", backendId));
        }
        List<String> cmd = new ArrayList<String>();
        cmd.add(exe.toAbsolutePath().toString());
        cmd.add("serve");
        cmd.add("-backend");
        cmd.add(backendId);
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            cmd.add("-O");
            cmd.add(e.getKey() + "=" + e.getValue());
        }
        cmd.add("-port");
        cmd.add(Integer.toString(localPort));
        if (opts.proxyProtocol != null && !opts.proxyProtocol.isEmpty()) {
            cmd.add("-proxy-protocol");
            cmd.add(opts.proxyProtocol);
        }
        if (opts.rendezvousPort > 0) {
            // Embedded signaling broker on loopback; serve resolves the
            // credential's brokers=origin placeholder to it.
            cmd.add("-rendezvous");
            cmd.add(Integer.toString(opts.rendezvousPort));
        }
        return cmd;
    }

    /**
     * 供日志输出的命令行描述。{@code -O} 形式的参数按键名判断：密钥类
     * （键名含 key/secret/token/password）只留键名，其余原样保留——房间名、
     * broker 列表与会合点端口正是排查「发布到哪去了」需要的。
     */
    public static String describe(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.size(); i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            String arg = cmd.get(i);
            sb.append(arg);
            if ("-O".equals(arg) && i + 1 < cmd.size()) {
                String kv = cmd.get(i + 1);
                int eq = kv.indexOf('=');
                String key = eq > 0 ? kv.substring(0, eq) : kv;
                if (sensitiveKey(key)) {
                    sb.append(' ').append(key).append("=***");
                } else {
                    sb.append(' ').append(kv);
                }
                i++;
            }
        }
        return sb.toString();
    }

    /** Keys whose values must never reach a log line. */
    private static boolean sensitiveKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("key") || k.contains("secret") || k.contains("token")
                || k.contains("password");
    }
}
