package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.Timings;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final String[] DEFAULT_RENDEZVOUS_PARAMS = {
        "# 默认内嵌会合点所需参数，保持原样即可",
        "token=auto",
        "room=minecraft",
        "secret=auto"
    };

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

    // ---- telemetry ----
    /**
     * 遥测总开关。默认开启，但缺省时刻意不写进 cfg；只有用户手工加入
     * telemetry.enable 时才读取并保留该 entry。与 enhanced 同为 false
     * 时才彻底关闭。
     */
    private final boolean telemetryEnabled;
    /** 是否包含详细的连接质量维度；这个开关正常生成在 cfg 中。 */
    private final boolean telemetryEnhanced;

    /**
     * 游戏入口统一走这里。即使 Forge 的配置 API 遇到未预料到的旧字段或坏值，
     * 也只会停用服务端功能并让客户端使用默认值，不能把整个游戏启动过程带崩。
     */
    public static ModConfig loadSafely(File file) {
        try {
            return new ModConfig(file);
        } catch (RuntimeException e) {
            LOG.error("配置应用失败，本次服务端功能关闭、客户端使用默认值: " + file, e);
            return new ModConfig(new LoadedConfiguration(new Configuration(), false));
        }
    }

    public ModConfig(File file) {
        this(loadConfiguration(file));
    }

    private static LoadedConfiguration loadConfiguration(File file) {
        // 服主会按 README 手写这个文件（免得为生成骨架先空跑一次游戏），
        // 手写就可能有语法错误。Forge 会尝试备份并重建纯语法错误的文件，
        // 但其余装载期 RuntimeException 仍可能逃逸，不能让它带崩游戏/服务端。
        // 退回默认值（不下发凭证、客户端默认参数）比炸掉体面得多。
        try {
            return new LoadedConfiguration(new Configuration(file), true);
        } catch (RuntimeException e) {
            LOG.error("配置文件解析失败，本次以默认值运行（改好语法后重启生效）: " + file, e);
            return new LoadedConfiguration(new Configuration(), false);
        }
    }

    private ModConfig(LoadedConfiguration loaded) {
        Configuration cfg = loaded.configuration;
        boolean loadedOk = loaded.loadedOk;

        cfg.setCategoryComment("server",
                "服务端专用。新生成的配置就是推荐的内嵌会合点模式，通常无需修改：\n"
                + "只要玩家正在使用的公网 TCP 地址能转发到 Minecraft 端口即可。\n"
                + "需要自建 frps 或更换隧道 backend 时再看 README 的高级配置。\n"
                + "注意：此文件含自动生成密钥的配置，权限只给服务端进程；\n"
                + "客户端不需要填写 server 类目。");
        // 新配置开箱即用；解析失败时仍然 fail closed，不能拿一套与服主原意
        // 可能完全不同的默认凭证对外提供服务。
        serverEnabled = cfg.getBoolean("enabled", "server", loadedOk,
                "服务端直连总开关。默认开启；设为 false 可完全关闭");
        serverRunAgent = cfg.getBoolean("runAgent", "server", true,
                "随服务端启动内置 serve。默认模式必须保持 true。\n"
                + "已在宿主机单独运行 netherway serve、或托管环境禁止启动子进程时设为 false");
        serverLocalPort = cfg.getInt("localPort", "server", 0, 0, 65535,
                "内置 serve 发布的 Minecraft 本地端口，0 表示使用服务器实际监听的端口");
        backendId = cfg.getString("backend", "server", Credentials.BACKEND_FRP_XTCP,
                "隧道方案标识。保持默认即可；只有实现了其它隧道方案时才修改");
        // 会合点开关必须先于 params 读出来：token=auto 只在会合点模式下成立
        // （经典模式的 token 是公网 frps 的 auth.token，本机生成毫无意义）。
        boolean rendezvousWanted = cfg.getBoolean("rendezvous", "server", true,
                "推荐且默认模式：在服务端进程内运行会合点。\n"
                + "玩家的控制连接从 Minecraft 公网入口进入，无需自建 frps 或部署 authplugin。\n"
                + "保持 runAgent=true；server.params 已带齐开箱即用的参数。\n"
                + "只有改用自建 frps 时才设为 false，并按 README 替换整个 params 列表");
        if (rendezvousWanted && !serverRunAgent) {
            // 光警告不够：serverCredentials 会据此摘掉地址，若这里仍按开启处理，
            // 就会下发「缺地址却没有会合点」的凭证，玩家全员打洞失败，
            // 而服主看日志只会以为自己在经典模式。所以直接按关闭处理。
            LOG.warn("server.rendezvous 需要 server.runAgent=true（会合点起在内置 serve "
                    + "进程里，独立运行的 serve 不会开它）。本次按未启用处理");
        }
        serverRendezvous = rendezvousWanted && serverRunAgent;

        Map<String, String> params = parseParams(cfg.getStringList("params", "server",
                DEFAULT_RENDEZVOUS_PARAMS.clone(),
                "隧道参数，每行一个 key=value。默认三项已经可用。\n"
                + "自建 frps 时才按 README 替换整个列表；高级鉴权项不写在这里"),
                backendId);
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
        // token=auto：同理，但只对内嵌会合点有意义——那个令牌只在服务端进程内
        // 被校验，本机生成即可，重启轮换让旧凭证自然失效。经典模式下 token 是
        // 公网 frps 的 auth.token，必须与那台机器一致，生成一个只会全员登录失败。
        if ("auto".equals(params.get("token"))) {
            if (serverRendezvous) {
                params.put("token", randomSecret());
                if (serverEnabled) {
                    LOG.info("token=auto：本次启动已生成随机会合点令牌，服务端每次重启轮换，"
                            + "玩家侧无需任何操作");
                }
            } else {
                LOG.warn("token=auto 只在 server.rendezvous=true 时有意义（经典模式的 token "
                        + "必须与公网 frps 的 auth.token 一致）。已按字面值 \"auto\" 使用，"
                        + "这几乎肯定不是你想要的");
            }
        }
        serverParams = params;
        serverPunchTimeoutSeconds = cfg.getInt("punchTimeoutSeconds", "server", 0, 0, 3600,
                "建议客户端使用的打洞超时秒数，0 表示由客户端自己配置");
        // 每玩家鉴权是高级功能。默认配置不生成这三项，避免它们与
        // server.params 里的会合点令牌挤在一起，让新手误以为必须挑一处填写。
        // 用户手工加入任意一项后再全部交给 Forge 管理、补注释和范围校验。
        boolean advancedAuthConfigured = cfg.hasKey("server", "tokenSigningKey")
                || cfg.hasKey("server", "tokenTtlDays")
                || cfg.hasKey("server", "serveAuthToken");
        if (advancedAuthConfigured) {
            tokenSigningKey = cfg.getString("tokenSigningKey", "server", "",
                    "【高级鉴权项】每玩家令牌签发密钥。内嵌会合点可直接使用；\n"
                    + "自建 frps 时须与 authplugin 的 -key 一致。\n"
                    + "它不是 server.params 中的会合点令牌；具体部署见 README");
            tokenTtlDays = cfg.getInt("tokenTtlDays", "server", 30, 1, 3650,
                    "【高级鉴权项】每玩家令牌的有效天数；每次登录自动续签");
            serveAuthToken = cfg.getString("serveAuthToken", "server", "",
                    "【自建 frps 高级项】内置 serve 的静态身份令牌，\n"
                    + "须与 authplugin 的 -static-token 一致，绝不能放进 server.params");
        } else {
            tokenSigningKey = "";
            tokenTtlDays = 30;
            serveAuthToken = "";
        }
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
                + "不做身份验证——准入交给 MC 服务端自己的白名单与正版验证。\n"
                + "注意：交换帧不加密，凭证以明文过网");
        // Forge 默认按字母排序，导致新人先看到 backend/localPort，再在文件末尾
        // 才发现模式开关。把「能否直接用」的四项放在 server 类目最前面。
        // Forge 1.7.10 会把配置里未列出的旧字段/拼错字段追加到传入列表。
        // Arrays.asList 返回定长列表，此时 add 会抛 UnsupportedOperationException；
        // 必须交给它一个真正可变的副本。
        cfg.setCategoryPropertyOrder("server", new ArrayList<String>(Arrays.asList(
                "enabled", "rendezvous", "runAgent", "params",
                "backend", "localPort", "preauth", "punchTimeoutSeconds",
                "proxyProtocol", "tokenSigningKey", "tokenTtlDays", "serveAuthToken")));

        cfg.setCategoryComment("client",
                "客户端专用：时间参数默认值来自实测，顺利时建链约 2-5 秒。\n"
                + "默认行为是全自动直连：启动时预取凭证（prefetch）、后台打洞（prewarm，\n"
                + "打不通按退避一直重试），玩家只管点服务器列表里的直连条目。");
        clientEnabled = cfg.getBoolean("enabled", "client", true,
                "是否响应服务端下发的凭证并尝试直连");
        clientPrewarm = cfg.getBoolean("prewarm", "client", true,
                "游戏启动时为每份已知服务凭证预热一条直连隧道，并在服务器列表里\n"
                + "维护对应直连条目。打洞严格串行以避免同 NAT 干扰，已建立隧道可同时存活；\n"
                + "失败服务各自按退避周期持续重试。关闭后已添加条目需手动删除");
        prewarmPort = cfg.getInt("prewarmPort", "client", 25595, 0, 65535,
                "预热隧道的本地端口，被占用时自动改用空闲端口（条目地址会跟着更新）；\n"
                + "0 表示每次随机");
        directEntryName = cfg.getString("directEntryName", "client", "[P2P直连]",
                "服务器列表中直连条目的名字前缀；每个服务会追加房间与来源入口。\n"
                + "改动后旧名字的条目不再被维护，需手动删除");
        clientPrefetch = cfg.getBoolean("prefetch", "client", true,
                "启动时直接向 Minecraft 服务器端口预取凭证，\n"
                + "首次启动、密钥轮换后都无需先经中转进服。\n"
                + "服务端没开 server.preauth 时本项静默不生效");
        prefetchServers = cfg.getStringList("prefetchServers", "client", new String[0],
                "预取凭证时要问的 Minecraft 服务器地址，每行一个 host 或 host:port\n"
                + "（不填端口按 25565）。留空则用服务器列表（server.dat）里的条目。\n"
                + "所有成功应答的地址都会被缓存并建立各自的直连条目");
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
                60, 5, 600, "单个候选的预取超时秒数（含 TCP 往返）");

        cfg.setCategoryComment("telemetry",
                "匿名质量测量。发送版本、平台与粗粒度结果；视开关发送\n"
                + "打洞阶段、稳定失败码、重试次数、RTT/耗时桶、预热/升级/预取路径。");
        telemetryEnhanced = cfg.getBoolean("enhanced", "telemetry", true,
                "是否发送详细匿名质量指标");
        boolean masterEnabled = !cfg.hasKey("telemetry", "enable")
                || cfg.getBoolean("enable", "telemetry", true,
                        "");
        telemetryEnabled = masterEnabled || telemetryEnhanced;

        // 解析失败时绝不能回写：会用默认值覆盖服主手里只是语法有瑕疵的文件
        if (loadedOk && cfg.hasChanged()) {
            cfg.save();
        }
    }

    private static final class LoadedConfiguration {
        private final Configuration configuration;
        private final boolean loadedOk;

        private LoadedConfiguration(Configuration configuration, boolean loadedOk) {
            this.configuration = configuration;
            this.loadedOk = loadedOk;
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

    public Timings clientTimings() {
        return new Timings(clientPunchTimeoutSeconds * 1000L,
                probeIntervalMs, probeTimeoutMs, startupGraceMs)
                .withWarmupRetry(warmupRetryInitialSeconds * 1000L,
                        warmupRetryMaxSeconds * 1000L)
                .withPrefetchTimeout(prefetchTimeoutSeconds * 1000L);
    }

    public boolean telemetryEnabled() {
        return telemetryEnabled;
    }

    public boolean telemetryEnhanced() {
        return telemetryEnhanced;
    }
}
