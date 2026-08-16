package cn.ripplecraft.netherway.forge;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.L10n;
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

    /** 新配置的 params 默认值。注释行的语言跟随 general.language，不能是静态常量。 */
    private static String[] defaultRendezvousParams() {
        return new String[] {
            "# " + L10n.tr("cfg.server.params.defaultNote"),
            "token=auto",
            "room=minecraft",
            "secret=auto"
        };
    }

    // ---- general ----
    /** 界面与日志语言：auto / en / zh。auto 时客户端跟随游戏语言、服务端跟随系统 locale。 */
    private final String language;

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
    private final boolean replaceServerEntries;
    private final boolean redirectOnWarmReady;
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
            LOG.error(L10n.tr("config.applyFailed", file), e);
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
            LOG.error(L10n.tr("config.parseFailed", file), e);
            return new LoadedConfiguration(new Configuration(), false);
        }
    }

    private ModConfig(LoadedConfiguration loaded) {
        Configuration cfg = loaded.configuration;
        boolean loadedOk = loaded.loadedOk;

        // 语言必须最先读并立即生效：本构造器接下来的告警日志与 cfg 注释都要用它。
        // cfg 注释在文件首次生成时按此语言写死，此后不随语言热切换（只改注释
        // 文案不会触发 Forge 回写已有文件，ModConfigSelfTest 钉住这一点）。
        // 唯独 language 自己的注释保持双语：语言还没选出来时它也得读得懂。
        language = cfg.getString("language", "general", "auto",
                "Language for logs and messages / 日志与提示语言: auto | en | zh\n"
                + "auto: client follows the game language, server follows the system locale\n"
                + "auto: 客户端跟随游戏语言设置，服务端跟随系统区域设置");
        // 客户端的 auto 稍后由 ClientProxy 按游戏语言再精化一次
        L10n.use(language);

        cfg.setCategoryComment("server", L10n.tr("cfg.server.category"));
        // 新配置开箱即用；解析失败时仍然 fail closed，不能拿一套与服主原意
        // 可能完全不同的默认凭证对外提供服务。
        serverEnabled = cfg.getBoolean("enabled", "server", loadedOk,
                L10n.tr("cfg.server.enabled"));
        serverRunAgent = cfg.getBoolean("runAgent", "server", true,
                L10n.tr("cfg.server.runAgent"));
        serverLocalPort = cfg.getInt("localPort", "server", 0, 0, 65535,
                L10n.tr("cfg.server.localPort"));
        backendId = cfg.getString("backend", "server", Credentials.BACKEND_FRP_XTCP,
                L10n.tr("cfg.server.backend"));
        // 会合点开关必须先于 params 读出来：token=auto 只在会合点模式下成立
        // （经典模式的 token 是公网 frps 的 auth.token，本机生成毫无意义）。
        boolean rendezvousWanted = cfg.getBoolean("rendezvous", "server", true,
                L10n.tr("cfg.server.rendezvous"));
        if (rendezvousWanted && !serverRunAgent) {
            // 光警告不够：serverCredentials 会据此摘掉地址，若这里仍按开启处理，
            // 就会下发「缺地址却没有会合点」的凭证，玩家全员打洞失败，
            // 而服主看日志只会以为自己在经典模式。所以直接按关闭处理。
            LOG.warn(L10n.tr("config.rendezvousNeedsRunAgent"));
        }
        serverRendezvous = rendezvousWanted && serverRunAgent;

        Map<String, String> params = parseParams(cfg.getStringList("params", "server",
                defaultRendezvousParams(),
                L10n.tr("cfg.server.params")),
                backendId);
        // secret=auto：每次启动生成随机密钥，等于给玩家侧缓存的凭证上了
        // 「服务端重启周期」的有效期。前提是 runAgent=true——serve 与下发
        // 同源，独立运行的 serve 拿不到这里生成的值。
        if ("auto".equals(params.get("secret"))) {
            if (serverEnabled && !serverRunAgent) {
                LOG.warn(L10n.tr("config.secretAutoNeedsRunAgent"));
            }
            params.put("secret", randomSecret());
            if (serverEnabled) {
                LOG.info(L10n.tr("config.secretAutoGenerated"));
            }
        }
        // token=auto：同理，但只对内嵌会合点有意义——那个令牌只在服务端进程内
        // 被校验，本机生成即可，重启轮换让旧凭证自然失效。经典模式下 token 是
        // 公网 frps 的 auth.token，必须与那台机器一致，生成一个只会全员登录失败。
        if ("auto".equals(params.get("token"))) {
            if (serverRendezvous) {
                params.put("token", randomSecret());
                if (serverEnabled) {
                    LOG.info(L10n.tr("config.tokenAutoGenerated"));
                }
            } else {
                LOG.warn(L10n.tr("config.tokenAutoClassic"));
            }
        }
        serverParams = params;
        serverPunchTimeoutSeconds = cfg.getInt("punchTimeoutSeconds", "server", 0, 0, 3600,
                L10n.tr("cfg.server.punchTimeoutSeconds"));
        // 每玩家鉴权是高级功能。默认配置不生成这三项，避免它们与
        // server.params 里的会合点令牌挤在一起，让新手误以为必须挑一处填写。
        // 用户手工加入任意一项后再全部交给 Forge 管理、补注释和范围校验。
        boolean advancedAuthConfigured = cfg.hasKey("server", "tokenSigningKey")
                || cfg.hasKey("server", "tokenTtlDays")
                || cfg.hasKey("server", "serveAuthToken");
        if (advancedAuthConfigured) {
            tokenSigningKey = cfg.getString("tokenSigningKey", "server", "",
                    L10n.tr("cfg.server.tokenSigningKey"));
            tokenTtlDays = cfg.getInt("tokenTtlDays", "server", 30, 1, 3650,
                    L10n.tr("cfg.server.tokenTtlDays"));
            serveAuthToken = cfg.getString("serveAuthToken", "server", "",
                    L10n.tr("cfg.server.serveAuthToken"));
        } else {
            tokenSigningKey = "";
            tokenTtlDays = 30;
            serveAuthToken = "";
        }
        String pp = cfg.getString("proxyProtocol", "server", "",
                L10n.tr("cfg.server.proxyProtocol"));
        if (!pp.isEmpty() && !"v1".equals(pp) && !"v2".equals(pp)) {
            // 传给 serve 会让它启动即退（它也校验），但剥头组件却已挂上——
            // 半开状态最迷惑人，不如当场按关闭处理并把话说明白
            LOG.warn(L10n.tr("config.badProxyProtocol", pp));
            pp = "";
        }
        serveProxyProtocol = pp;
        serverPreauth = cfg.getBoolean("preauth", "server", true,
                L10n.tr("cfg.server.preauth"));
        // Forge 默认按字母排序，导致新人先看到 backend/localPort，再在文件末尾
        // 才发现模式开关。把「能否直接用」的四项放在 server 类目最前面。
        // Forge 1.7.10 会把配置里未列出的旧字段/拼错字段追加到传入列表。
        // Arrays.asList 返回定长列表，此时 add 会抛 UnsupportedOperationException；
        // 必须交给它一个真正可变的副本。
        cfg.setCategoryPropertyOrder("server", new ArrayList<String>(Arrays.asList(
                "enabled", "rendezvous", "runAgent", "params",
                "backend", "localPort", "preauth", "punchTimeoutSeconds",
                "proxyProtocol", "tokenSigningKey", "tokenTtlDays", "serveAuthToken")));

        cfg.setCategoryComment("client", L10n.tr("cfg.client.category"));
        clientEnabled = cfg.getBoolean("enabled", "client", true,
                L10n.tr("cfg.client.enabled"));
        clientPrewarm = cfg.getBoolean("prewarm", "client", true,
                L10n.tr("cfg.client.prewarm"));
        replaceServerEntries = cfg.getBoolean("replaceServerEntries", "client", true,
                L10n.tr("cfg.client.replaceServerEntries"));
        redirectOnWarmReady = cfg.getBoolean("redirectOnWarmReady", "client", true,
                L10n.tr("cfg.client.redirectOnWarmReady"));
        prewarmPort = cfg.getInt("prewarmPort", "client", 25595, 0, 65535,
                L10n.tr("cfg.client.prewarmPort"));
        directEntryName = cfg.getString("directEntryName", "client", "[P2P直连]",
                L10n.tr("cfg.client.directEntryName"));
        clientPrefetch = cfg.getBoolean("prefetch", "client", true,
                L10n.tr("cfg.client.prefetch"));
        prefetchServers = cfg.getStringList("prefetchServers", "client", new String[0],
                L10n.tr("cfg.client.prefetchServers"));
        verboseLogging = cfg.getBoolean("verboseLogging", "client", true,
                L10n.tr("cfg.client.verboseLogging"));
        clientPunchTimeoutSeconds = cfg.getInt("punchTimeoutSeconds", "client", 15, 1, 3600,
                L10n.tr("cfg.client.punchTimeoutSeconds"));
        probeIntervalMs = cfg.getInt("probeIntervalMs", "client", 250, 50, 10_000,
                L10n.tr("cfg.client.probeIntervalMs"));
        probeTimeoutMs = cfg.getInt("probeTimeoutMs", "client", 2_000, 100, 60_000,
                L10n.tr("cfg.client.probeTimeoutMs"));
        startupGraceMs = cfg.getInt("startupGraceMs", "client", 5_000, 0, 60_000,
                L10n.tr("cfg.client.startupGraceMs"));
        warmupRetryInitialSeconds = cfg.getInt("warmupRetryInitialSeconds", "client",
                10, 1, 3600, L10n.tr("cfg.client.warmupRetryInitialSeconds"));
        warmupRetryMaxSeconds = cfg.getInt("warmupRetryMaxSeconds", "client",
                120, 1, 86_400, L10n.tr("cfg.client.warmupRetryMaxSeconds"));
        prefetchTimeoutSeconds = cfg.getInt("prefetchTimeoutSeconds", "client",
                60, 5, 600, L10n.tr("cfg.client.prefetchTimeoutSeconds"));

        cfg.setCategoryComment("telemetry", L10n.tr("cfg.telemetry.category"));
        telemetryEnhanced = cfg.getBoolean("enhanced", "telemetry", true,
                L10n.tr("cfg.telemetry.enhanced"));
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

    // ---- general ----

    /** cfg 里的语言设定（auto/en/zh），auto 的精化交给调用方。 */
    public String language() {
        return language;
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
                LOG.warn(L10n.tr("config.paramNotKv"));
                continue;
            }
            params.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        // agent 对未知键的契约是静默忽略（服务端可能比 agent 新），
        // 所以拼写错误不会在任何一端报错——只能在这里主动指出来。
        if (Credentials.BACKEND_FRP_XTCP.equals(backendId)) {
            for (String key : params.keySet()) {
                if (!Credentials.frpXtcpParamKeys().contains(key)) {
                    LOG.warn(L10n.tr("config.unknownParamKey",
                            key, Credentials.frpXtcpParamKeys()));
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
            LOG.warn(L10n.tr("config.incompleteCred", e.getMessage()));
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

    public boolean replaceServerEntries() {
        return replaceServerEntries;
    }

    public boolean redirectOnWarmReady() {
        return redirectOnWarmReady;
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
