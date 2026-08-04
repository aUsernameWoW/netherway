package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.Timings;
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

    private static final Logger LOG = LogManager.getLogger(Netherway.MODID);

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
    private final String serveProxyProtocol;
    private final boolean serverPreauth;
    private final String serverAuthServer;
    private final boolean serverRendezvous;

    // ---- client ----
    private final boolean clientEnabled;
    private final boolean verboseLogging;
    private final boolean clientPrewarm;
    private final int prewarmPort;
    private final String directEntryName;
    private final boolean clientPrefetch;
    private final String[] prefetchServers;
    private final int clientPunchTimeoutSeconds;
    private final int probeIntervalMs;
    private final int probeTimeoutMs;
    private final int startupGraceMs;
    private final int warmupRetryInitialSeconds;
    private final int warmupRetryMaxSeconds;
    private final int prefetchTimeoutSeconds;

    // ---- experimental ----
    /** 运行中可被服务端下发的策略打开，因此不是 final。 */
    private volatile boolean zeroConfigPrefetch;
    private final boolean grantZeroConfigPrefetch;
    /** 留着以便把服务端授权的开关写回磁盘。 */
    private final File configFile;

    public ModConfig(File file) {
        this.configFile = file;
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
                + "已在宿主机单独运行 netherway serve、或托管环境禁止启动子进程时设为 false");
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
        serverRendezvous = cfg.getBoolean("rendezvous", "server", false,
                "内嵌会合点：不再连公网 frps，改在本机起一个只监听回环的会合点，\n"
                + "玩家的 frp 控制连接由本 mod 从 Minecraft 端口转发进去。\n"
                + "公网那台机器因此只需要把 TCP 转到 Minecraft 端口——不装插件、\n"
                + "不必支持 xtcp、不必与本 mod 同版本，租来的隧道服务也能用。\n"
                + "需要 runAgent=true；开启后 params 里的 server/serverPort 应填\n"
                + "玩家能连到的地址与端口（即 Minecraft 服务器的公网入口）");
        serveAuthToken = cfg.getString("serveAuthToken", "server", "",
                "内置 serve 向 authplugin 表明身份的静态令牌（与 authplugin 的 -static-token 同值），\n"
                + "刻意不放进 params——它只属于 serve，绝不能随凭证下发给玩家；\n"
                + "frps 未部署 authplugin 时留空");
        String pp = cfg.getString("proxyProtocol", "server", "",
                "让隧道进程连本地 MC 端口前先发 PROXY protocol 头（填 v1 或 v2，留空关闭）。\n"
                + "开启后本 mod 会给服务端接入链装嗅探式剥头组件，登录日志与封禁\n"
                + "看到的是玩家真实来源地址而不是 127.0.0.1。\n"
                + "当前 frp 只有 stcp 中转路径实际带头，xtcp 的 P2P 流等上游支持后自动生效。\n"
                + "runAgent=false 时须给独立运行的 serve 手动加同值的 -proxy-protocol 旗标");
        if (!pp.isEmpty() && !"v1".equals(pp) && !"v2".equals(pp)) {
            // 传给 serve 会让它启动即退（它也校验），但剥头组件却已挂上——
            // 半开状态最迷惑人，不如当场按关闭处理并把话说明白
            LOG.warn("server.proxyProtocol 只接受 v1 或 v2（当前值 \"{}\"），已按关闭处理", pp);
            pp = "";
        }
        serveProxyProtocol = pp;
        serverPreauth = cfg.getBoolean("preauth", "server", true,
                "允许玩家在进服之前于 Minecraft 端口上换取直连凭证（不另开监听端口）。\n"
                + "开启后玩家首次启动、密钥轮换之后都无需先经中转进服。\n"
                + "在线模式下经皮肤站 hasJoined 查证身份；online-mode=false 时无从查证，\n"
                + "准入沿用服务器自己的名单（白名单开着就查白名单）。\n"
                + "注意：交换帧不加密，凭证以明文过网");
        serverAuthServer = cfg.getString("authServer", "server", "",
                "皮肤站 API root，如 https://skin.example.com/api/yggdrasil；\n"
                + "预认证时服务端拿它查 hasJoined，并告知客户端去哪报到。\n"
                + "留空则从 authlib-injector 的 -javaagent 参数里自动读取；\n"
                + "online-mode=false 时本项无用");

        cfg.setCategoryComment("client",
                "客户端专用：时间参数默认值来自实测，顺利时建链约 2-5 秒。\n"
                + "默认行为是全自动直连：启动时预取凭证（prefetch）、后台打洞（prewarm，\n"
                + "打不通按退避一直重试），玩家只管点服务器列表里的直连条目。");
        clientEnabled = cfg.getBoolean("enabled", "client", true,
                "是否响应服务端下发的凭证并尝试直连");
        clientPrewarm = cfg.getBoolean("prewarm", "client", true,
                "游戏启动时预热直连隧道（凭证来自预取或上次缓存），并在服务器列表里\n"
                + "维护一个直连条目，打通后直接选它进服；打不通会按退避周期持续重试。\n"
                + "关闭后已添加的条目不会被自动删除，手动删即可");
        prewarmPort = cfg.getInt("prewarmPort", "client", 25595, 0, 65535,
                "预热隧道的本地端口，被占用时自动改用空闲端口（条目地址会跟着更新）；\n"
                + "0 表示每次随机");
        directEntryName = cfg.getString("directEntryName", "client", "[P2P直连]",
                "服务器列表中直连条目的名字前缀，同时用于识别并更新该条目；\n"
                + "整合包可改成服务器名让条目看起来就是「那个服务器」。\n"
                + "改动后旧名字的条目不再被维护，需手动删除");
        clientPrefetch = cfg.getBoolean("prefetch", "client", true,
                "启动时直接向 Minecraft 服务器端口预取凭证（用游戏会话走一遍正版验证），\n"
                + "首次启动、密钥轮换后都无需先经中转进服。\n"
                + "服务端没开 server.preauth 时本项静默不生效");
        prefetchServers = cfg.getStringList("prefetchServers", "client", new String[0],
                "预取凭证时要问的 Minecraft 服务器地址，每行一个 host 或 host:port\n"
                + "（不填端口按 25565）。留空则用服务器列表（server.dat）里的条目。\n"
                + "整合包可以在这里预置服务器地址，玩家连服务器列表都不必自己加");
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
        warmupRetryInitialSeconds = cfg.getInt("warmupRetryInitialSeconds", "client",
                10, 1, 3600, "预热打洞失败后的首次重试等待秒数（此后指数退避）");
        warmupRetryMaxSeconds = cfg.getInt("warmupRetryMaxSeconds", "client",
                120, 1, 86_400, "预热重试退避的上限秒数——打不通就按这个周期一直打");
        prefetchTimeoutSeconds = cfg.getInt("prefetchTimeoutSeconds", "client",
                60, 5, 600, "单个候选的预取超时秒数（含 TCP 往返与一次皮肤站报到）");

        cfg.setCategoryComment("experimental",
                "实验性开关，默认全关。开之前请读完每一项的说明。");
        zeroConfigPrefetch = cfg.getBoolean("zeroConfigPrefetch", "experimental", false,
                "【零配置预取】关闭时，预取只问 client.prefetchServers 里写明的地址；\n"
                + "开启后，该项为空时改为扫描服务器列表（server.dat），\n"
                + "对里面的每个服务器都试一次预认证。\n"
                + "\n"
                + "原理: 预认证帧与 Minecraft 流量共用服务器那一个端口，靠首字节分辨\n"
                + "（帧以 NWAY 开头，MC 握手第 2 字节是包 id 0x00）。没开预认证的\n"
                + "服务器会把这个帧当成坏包直接断开，对客户端就是「这个候选不应答」。\n"
                + "好处是玩家什么都不用填，服务器列表里有地址就能直连。\n"
                + "\n"
                + "风险:\n"
                + "1. 会把你的玩家名与 UUID 发给服务器列表里的每一个服务器，\n"
                + "   包括与本 mod 毫无关系的那些——它们能得知你启动了游戏。\n"
                + "2. 预认证帧不加密：同一条网络路径上的人能看到往来内容，\n"
                + "   换回的凭证（房间密钥、frps 令牌）也是明文。\n"
                + "3. 应答者未经任何验证。恶意服务器可以回一份指向它自己 frps 的\n"
                + "   凭证，你的预热隧道就会连到它那里（隧道只通向 MC 端口，\n"
                + "   且要你真的去点那个直连条目才会用上）。\n"
                + "   accessToken 不在此列: 它只发给本机 authlib-injector 认的皮肤站，\n"
                + "   服务器指定的地址一律不采信。\n"
                + "\n"
                + "服务端可以下发策略把这一项打开并写回本文件——那种情况下\n"
                + "应答者是你确实登录过的服务器，风险 3 基本不存在。");
        grantZeroConfigPrefetch = cfg.getBoolean("grantZeroConfigPrefetch", "experimental",
                false,
                "【服务端专用】玩家登录后，随凭证下发一条策略，把该玩家客户端的\n"
                + "zeroConfigPrefetch 打开并写回它的配置文件。\n"
                + "意义: 玩家经中转进过一次服之后，以后每次启动都能自动预取，\n"
                + "不必手填 client.prefetchServers。此时应答者是玩家真正登录过的\n"
                + "服务器，比让他盲扫整个服务器列表安全得多。\n"
                + "客户端侧仍可手动关掉——这只是授权，不是强制。");

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
            Map<String, String> p = serverParams;
            if (serverRendezvous) {
                // 内嵌会合点模式下地址由客户端自己补：会合点就在这台服务器的
                // Minecraft 端口后面，玩家知道自己连的是哪；服务端反而未必知道
                // 自己的公网入口（NAT 后、多入口、域名与实际入口不一致都常见），
                // 把配置里可能陈旧的地址发下去只会让客户端连错地方。
                p = new java.util.LinkedHashMap<String, String>(p);
                p.remove("server");
                p.remove("serverPort");
            }
            return new Credentials(backendId, p, serverPunchTimeoutSeconds * 1000);
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

    /** PROXY protocol 版本（"v1"/"v2"），空串表示关闭。 */
    public String serveProxyProtocol() {
        return serveProxyProtocol;
    }

    /** 是否在 MC 端口上接受预认证帧。 */
    public boolean serverPreauth() {
        return serverPreauth;
    }

    /**
     * 是否启用内嵌会合点。开启后 agent 不连公网 frps，会合点起在本机回环上，
     * 玩家的控制连接由 {@link ConnectionSniffer} 从 Minecraft 端口转发进去。
     */
    public boolean serverRendezvous() {
        return serverRendezvous;
    }

    /** 皮肤站 API root；空串表示交给 authlib-injector 参数自动推断。 */
    public String serverAuthServer() {
        return serverAuthServer;
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

    public boolean clientPrefetch() {
        return clientPrefetch;
    }

    /** cfg 里预置的 Minecraft 服务器地址；空数组表示改用 server.dat 里的条目。 */
    public String[] prefetchServers() {
        return prefetchServers.clone();
    }

    // ---- experimental ----

    /** 预取候选为空时是否可以退回扫描服务器列表。 */
    public boolean zeroConfigPrefetch() {
        return zeroConfigPrefetch;
    }

    /** 服务端是否要授权客户端开启零配置预取。 */
    public boolean grantZeroConfigPrefetch() {
        return grantZeroConfigPrefetch;
    }

    /**
     * 采纳服务端下发的「可以零配置预取」授权，并写回配置文件，下次启动继续生效。
     *
     * <p>已经开着就什么都不做——避免每次登录都去动一次磁盘。写盘失败只记日志：
     * 本次会话照常生效，无非是下次启动还要再被授权一遍。
     *
     * @return 本次调用是否真的改变了状态
     */
    public boolean adoptZeroConfigPrefetch() {
        if (zeroConfigPrefetch) {
            return false;
        }
        zeroConfigPrefetch = true;
        try {
            Configuration cfg = new Configuration(configFile);
            cfg.get("experimental", "zeroConfigPrefetch", false).set(true);
            cfg.save();
        } catch (RuntimeException e) {
            LOG.warn("服务端已授权零配置预取，但写回配置文件失败"
                    + "（本次会话仍生效，下次启动需重新授权）", e);
        }
        return true;
    }

    public Timings clientTimings() {
        return new Timings(clientPunchTimeoutSeconds * 1000L,
                probeIntervalMs, probeTimeoutMs, startupGraceMs)
                .withWarmupRetry(warmupRetryInitialSeconds * 1000L,
                        warmupRetryMaxSeconds * 1000L)
                .withPrefetchTimeout(prefetchTimeoutSeconds * 1000L);
    }
}
