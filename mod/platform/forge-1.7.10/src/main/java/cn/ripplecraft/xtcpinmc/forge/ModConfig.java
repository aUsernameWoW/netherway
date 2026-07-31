package cn.ripplecraft.xtcpinmc.forge;

import cn.ripplecraft.xtcpinmc.core.Credentials;
import cn.ripplecraft.xtcpinmc.core.Timings;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * mod 配置。同一个文件服务两侧：{@code server} 类目只被服务端读，
 * {@code client} 类目只被客户端读，装错侧的那半安静地闲置。
 *
 * <p>server.params 是通用的 key=value 列表而非 frp 专用字段——凭证自 v2 起
 * 就是「backend 标识 + 参数表」，配置也保持同构，将来换隧道方案时
 * 这里零改动。键名契约见 Go 侧对应的 backend 实现包。
 *
 * <p>这个类不引用任何 Minecraft 类型（Forge 的 Configuration 不算），
 * 服务端与客户端都能加载。
 */
public final class ModConfig {

    private static final Logger LOG = LogManager.getLogger(XtcpInMc.MODID);

    // ---- server ----
    private final boolean serverEnabled;
    private final boolean serverRunAgent;
    private final int serverLocalPort;
    private final String backendId;
    /** 构造时解析完的参数表；解析与拼写检查只做一次，避免每次登录重复告警。 */
    private final Map<String, String> serverParams;
    private final int serverPunchTimeoutSeconds;
    private final String tokenSigningKey;
    private final int tokenTtlDays;
    private final String serveAuthToken;

    // ---- client ----
    private final boolean clientEnabled;
    private final boolean verboseLogging;
    private final boolean clientPrewarm;
    private final int prewarmPort;
    private final String directEntryName;
    private final int clientPunchTimeoutSeconds;
    private final int probeIntervalMs;
    private final int probeTimeoutMs;
    private final int startupGraceMs;

    public ModConfig(File file) {
        // 服主会按 README 手写这个文件（免得为生成骨架先空跑一次游戏），
        // 手写就可能有语法错误——1.7.10 的 Configuration 解析失败会直接抛
        // RuntimeException，不接住的话整个游戏/服务端就起不来了。
        // 退回默认值（不下发凭证、客户端默认参数）比炸掉体面得多。
        Configuration cfg;
        boolean loadedOk = true;
        try {
            cfg = new Configuration(file);
        } catch (RuntimeException e) {
            LOG.error("配置文件解析失败，本次以默认值运行（改好语法后重启生效）: " + file, e);
            cfg = new Configuration();
            loadedOk = false;
        }

        cfg.setCategoryComment("server",
                "服务端专用：开启后在玩家登录时下发直连凭证。\n"
                + "params 的键名契约由 backend 决定，frp-xtcp 需要:\n"
                + "  server=<frps地址> serverPort=<frps端口> token=<frps令牌>\n"
                + "  stun=<STUN候选,逗号分隔> room=<房间名> secret=<房间密钥>\n"
                + "secret=auto 表示每次启动随机生成密钥（推荐，须 runAgent=true）：\n"
                + "玩家缓存的旧凭证随服务端重启失效，走一次中转即自动拿到新密钥。\n"
                + "注意: 此文件含密钥，权限只给服务端进程；客户端永远不需要填这些。");
        serverEnabled = cfg.getBoolean("enabled", "server", false,
                "是否在玩家登录后下发直连凭证（仅服务端有意义）");
        serverRunAgent = cfg.getBoolean("runAgent", "server", true,
                "随服务端启动内置 serve，用 params 里的参数把本地端口注册为房间代理。\n"
                + "已在宿主机单独运行 xtcpinmc serve、或托管环境禁止启动子进程时设为 false");
        serverLocalPort = cfg.getInt("localPort", "server", 0, 0, 65535,
                "内置 serve 发布的 Minecraft 本地端口，0 表示使用服务器实际监听的端口");
        backendId = cfg.getString("backend", "server", Credentials.BACKEND_FRP_XTCP,
                "隧道方案标识，与 agent 的 -backend 一致");
        Map<String, String> params = parseParams(cfg.getStringList("params", "server",
                new String[0], "backend 参数，每行一个 key=value"), backendId);
        // secret=auto：每次启动生成随机密钥，等于给玩家侧缓存的凭证上了
        // 「服务端重启周期」的有效期。前提是 runAgent=true——serve 与下发
        // 同源，独立运行的 serve 拿不到这里生成的值。
        if ("auto".equals(params.get("secret"))) {
            if (serverEnabled && !serverRunAgent) {
                LOG.warn("secret=auto 需要 server.runAgent=true（内置 serve 与下发凭证同源）；"
                        + "独立运行的 serve 无法得知本次生成的密钥，玩家会一直打洞失败");
            }
            params.put("secret", randomSecret());
            if (serverEnabled) {
                LOG.info("secret=auto：本次启动已生成随机房间密钥，服务端每次重启轮换，"
                        + "玩家侧无需任何操作");
            }
        }
        serverParams = params;
        serverPunchTimeoutSeconds = cfg.getInt("punchTimeoutSeconds", "server", 0, 0, 3600,
                "建议客户端使用的打洞超时秒数，0 表示由客户端自己配置");
        tokenSigningKey = cfg.getString("tokenSigningKey", "server", "",
                "每玩家令牌的签发密钥，非空即启用；须与 frps 侧 authplugin 的 -key 一致。\n"
                + "启用后每次登录都为该玩家签发绑定其 UUID、带有效期的令牌（user/userToken 参数）");
        tokenTtlDays = cfg.getInt("tokenTtlDays", "server", 30, 1, 3650,
                "每玩家令牌的有效天数；每次登录自动续签，只需覆盖玩家两次游玩的间隔");
        serveAuthToken = cfg.getString("serveAuthToken", "server", "",
                "内置 serve 向 authplugin 表明身份的静态令牌（与 authplugin 的 -static-token 同值），\n"
                + "刻意不放进 params——它只属于 serve，绝不能随凭证下发给玩家；\n"
                + "frps 未部署 authplugin 时留空");

        cfg.setCategoryComment("client",
                "客户端专用：时间参数默认值来自实测，顺利时建链约 2-5 秒。");
        clientEnabled = cfg.getBoolean("enabled", "client", true,
                "是否响应服务端下发的凭证并尝试直连");
        clientPrewarm = cfg.getBoolean("prewarm", "client", true,
                "游戏启动时用上次缓存的凭证预热直连隧道，并在服务器列表里维护一个直连条目，\n"
                + "打通后可直接选它进服。首次进服仍需先经中转拿到凭证；\n"
                + "关闭后已添加的条目不会被自动删除，手动删即可");
        prewarmPort = cfg.getInt("prewarmPort", "client", 25595, 0, 65535,
                "预热隧道的本地端口，被占用时自动改用空闲端口（条目地址会跟着更新）；\n"
                + "0 表示每次随机");
        directEntryName = cfg.getString("directEntryName", "client", "[P2P直连]",
                "服务器列表中直连条目的名字前缀，同时用于识别并更新该条目；\n"
                + "改动后旧名字的条目不再被维护，需手动删除");
        verboseLogging = cfg.getBoolean("verboseLogging", "client", true,
                "把直连过程的详细日志（agent 事件、参数键、诊断输出）以 INFO 级别写进游戏日志；\n"
                + "关闭后这些内容降为 DEBUG 级别（默认日志配置下不可见）");
        clientPunchTimeoutSeconds = cfg.getInt("punchTimeoutSeconds", "client", 15, 1, 3600,
                "打洞超时秒数（服务端下发了建议值时以服务端为准）");
        probeIntervalMs = cfg.getInt("probeIntervalMs", "client", 250, 50, 10_000,
                "就绪探测间隔毫秒数");
        probeTimeoutMs = cfg.getInt("probeTimeoutMs", "client", 2_000, 100, 60_000,
                "单次就绪探测超时毫秒数");
        startupGraceMs = cfg.getInt("startupGraceMs", "client", 5_000, 0, 60_000,
                "打洞超时之外留给进程启动、二进制释放的余量毫秒数");

        // 解析失败时绝不能回写：会用默认值覆盖服主手里只是语法有瑕疵的文件
        if (loadedOk && cfg.hasChanged()) {
            cfg.save();
        }
    }

    // ---- server ----

    public boolean serverEnabled() {
        return serverEnabled;
    }

    public boolean serverRunAgent() {
        return serverRunAgent;
    }

    public int serverLocalPort() {
        return serverLocalPort;
    }

    public String serverBackendId() {
        return backendId;
    }

    /** 解析完的 backend 参数表（与下发凭证同源），内置 serve 的命令行由它组装。 */
    public Map<String, String> serverParams() {
        return serverParams;
    }

    private static Map<String, String> parseParams(String[] lines, String backendId) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                LOG.warn("server.params 中的行不是 key=value 形式，已忽略一行");
                continue;
            }
            params.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        // agent 对未知键的契约是静默忽略（服务端可能比 agent 新），
        // 所以拼写错误不会在任何一端报错——只能在这里主动指出来。
        if (Credentials.BACKEND_FRP_XTCP.equals(backendId)) {
            for (String key : params.keySet()) {
                if (!Credentials.frpXtcpParamKeys().contains(key)) {
                    LOG.warn("server.params 里的键 \"{}\" 不在 frp-xtcp 的契约里"
                            + "（认识的键: {}），agent 会忽略它；若是拼写错误请改正",
                            key, Credentials.frpXtcpParamKeys());
                }
            }
        }
        return params;
    }

    /**
     * 由配置组装凭证；配置不完整返回 null。
     *
     * <p>校验交给 {@link Credentials} 的构造器（比如 room 必填、键不含等号），
     * 它的报错信息只含键名不含值，可以放心进日志。
     */
    public Credentials serverCredentials() {
        try {
            return new Credentials(backendId, serverParams, serverPunchTimeoutSeconds * 1000);
        } catch (IllegalArgumentException e) {
            LOG.warn("凭证配置不完整: {}", e.getMessage());
            return null;
        }
    }

    public String tokenSigningKey() {
        return tokenSigningKey;
    }

    public int tokenTtlDays() {
        return tokenTtlDays;
    }

    public String serveAuthToken() {
        return serveAuthToken;
    }

    /** 32 位十六进制随机密钥，密码学强度随机源。 */
    private static String randomSecret() {
        byte[] raw = new byte[16];
        new java.security.SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ---- client ----

    public boolean clientEnabled() {
        return clientEnabled;
    }

    public boolean clientPrewarm() {
        return clientPrewarm;
    }

    public int prewarmPort() {
        return prewarmPort;
    }

    public String directEntryName() {
        return directEntryName;
    }

    public boolean verboseLogging() {
        return verboseLogging;
    }

    public Timings clientTimings() {
        return new Timings(clientPunchTimeoutSeconds * 1000L,
                probeIntervalMs, probeTimeoutMs, startupGraceMs);
    }
}
