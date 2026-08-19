package cn.ripplecraft.netherway.modern;

import cn.ripplecraft.netherway.core.Credentials;
import cn.ripplecraft.netherway.core.L10n;
import cn.ripplecraft.netherway.core.Timings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * mod 配置，modern（1.16.5+）各平台共用。与 forge-1.7.10 的 ModConfig
 * 逐键同构：同一个 {@code netherway.cfg}、同一套键名与语义，服主跨版本
 * 迁移时配置与文档完全不变。解析走自研 {@link CfgFile}（Forge 的
 * {@code Configuration} 在 1.13+ 已删除，Fabric 从来没有）。
 *
 * <p>这个类不引用任何 Minecraft 类型，服务端与客户端、Forge 与 Fabric
 * 都能加载。改键时三处同步：forge-1.7.10 与 forge-1.12.2 的 ModConfig、
 * 本类，以及 README 的配置文档。
 */
public final class ModConfig {

    private static final Logger LOG = LogManager.getLogger(NetherwayModern.MODID);

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
    private final String language;

    // ---- server ----
    private final boolean serverEnabled;
    private final boolean serverRunAgent;
    private final int serverLocalPort;
    private final String backendId;
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
    private final int prefetchRefreshSeconds;

    // ---- telemetry ----
    private final boolean telemetryEnabled;
    private final boolean telemetryEnhanced;

    /**
     * 游戏入口统一走这里。解析遇到任何意外只会停用服务端功能并让客户端
     * 使用默认值，不能把游戏启动过程带崩。
     */
    public static ModConfig loadSafely(Path file) {
        try {
            return new ModConfig(file);
        } catch (RuntimeException e) {
            LOG.error(L10n.tr("config.applyFailed", file), e);
            return new ModConfig(new CfgFile(), null, false);
        }
    }

    private ModConfig(Path file) {
        this(loadCfg(file), file, true);
    }

    private static CfgFile loadCfg(Path file) {
        // 服主会按 README 手写这个文件，手写就可能有语法错误。
        // 解析失败退回默认值（不下发凭证、客户端默认参数），绝不回写覆盖。
        try {
            return new CfgFile(file);
        } catch (java.io.IOException e) {
            LOG.error(L10n.tr("config.parseFailed", file), e);
            throw new IllegalStateException(e);
        }
    }

    private ModConfig(CfgFile cfg, Path file, boolean loadedOk) {
        // 语言必须最先读并立即生效：本构造器接下来的告警日志与 cfg 注释都要用它。
        // 唯独 language 自己的注释保持双语：语言还没选出来时它也得读得懂。
        language = cfg.getString("language", "general", "auto",
                "Language for logs and messages / 日志与提示语言: auto | en | zh\n"
                + "auto: client follows the game language, server follows the system locale\n"
                + "auto: 客户端跟随游戏语言设置，服务端跟随系统区域设置");
        L10n.use(language);

        cfg.setCategoryComment("server", L10n.tr("cfg.server.category"));
        // 新配置开箱即用；解析失败时仍然 fail closed。
        serverEnabled = cfg.getBoolean("enabled", "server", loadedOk,
                L10n.tr("cfg.server.enabled"));
        serverRunAgent = cfg.getBoolean("runAgent", "server", true,
                L10n.tr("cfg.server.runAgent"));
        serverLocalPort = cfg.getInt("localPort", "server", 0, 0, 65535,
                L10n.tr("cfg.server.localPort"));
        backendId = cfg.getString("backend", "server", Credentials.BACKEND_FRP_XTCP,
                L10n.tr("cfg.server.backend"));
        boolean rendezvousWanted = cfg.getBoolean("rendezvous", "server", true,
                L10n.tr("cfg.server.rendezvous"));
        if (rendezvousWanted && !serverRunAgent) {
            // 见 forge-1.7.10 同名逻辑：半开状态会下发「缺地址却没有会合点」
            // 的凭证，比直接按关闭处理更害人。
            LOG.warn(L10n.tr("config.rendezvousNeedsRunAgent"));
        }
        boolean frpBackend = Credentials.BACKEND_FRP_XTCP.equals(backendId);
        if (rendezvousWanted && serverRunAgent && !frpBackend) {
            // rendezvous=true 是默认值，gonc-p2p 服主大概率只改了 backend 一项，
            // 这里按关闭处理并说明原因（info 级，不是配置错误）。
            LOG.info(L10n.tr("config.rendezvousFrpOnly", backendId));
        }
        serverRendezvous = rendezvousWanted && serverRunAgent && frpBackend;

        Map<String, String> params = parseParams(cfg.getStringList("params", "server",
                defaultRendezvousParams(),
                L10n.tr("cfg.server.params")),
                backendId);
        if ("auto".equals(params.get("secret"))) {
            if (serverEnabled && !serverRunAgent) {
                LOG.warn(L10n.tr("config.secretAutoNeedsRunAgent"));
            }
            params.put("secret", randomSecret());
            if (serverEnabled) {
                LOG.info(L10n.tr("config.secretAutoGenerated"));
            }
        }
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
        // sessionKey=auto：gonc-p2p 的房间密钥，与 secret=auto 同一套逻辑——
        // serve 与下发凭证同源，重启即轮换，玩家侧经缓存自愈闭环拿新密钥。
        if ("auto".equals(params.get("sessionKey"))) {
            if (serverEnabled && !serverRunAgent) {
                LOG.warn(L10n.tr("config.sessionKeyAutoNeedsRunAgent"));
            }
            params.put("sessionKey", randomSecret());
            if (serverEnabled) {
                LOG.info(L10n.tr("config.sessionKeyAutoGenerated"));
            }
        }
        serverParams = params;
        serverPunchTimeoutSeconds = cfg.getInt("punchTimeoutSeconds", "server", 0, 0, 3600,
                L10n.tr("cfg.server.punchTimeoutSeconds"));
        boolean advancedAuthConfigured = cfg.hasKey("server", "tokenSigningKey")
                || cfg.hasKey("server", "tokenTtlDays")
                || cfg.hasKey("server", "serveAuthToken");
        String signingKey;
        if (advancedAuthConfigured) {
            signingKey = cfg.getString("tokenSigningKey", "server", "",
                    L10n.tr("cfg.server.tokenSigningKey"));
            tokenTtlDays = cfg.getInt("tokenTtlDays", "server", 30, 1, 3650,
                    L10n.tr("cfg.server.tokenTtlDays"));
            serveAuthToken = cfg.getString("serveAuthToken", "server", "",
                    L10n.tr("cfg.server.serveAuthToken"));
        } else {
            signingKey = "";
            tokenTtlDays = 30;
            serveAuthToken = "";
        }
        if (!frpBackend && !signingKey.isEmpty()) {
            // 每玩家令牌由 frps 侧 authplugin 校验，gonc-p2p 下没有那道关卡。
            LOG.warn(L10n.tr("config.tokenSigningFrpOnly", backendId));
            signingKey = "";
        }
        tokenSigningKey = signingKey;
        String pp = cfg.getString("proxyProtocol", "server", "",
                L10n.tr("cfg.server.proxyProtocol"));
        if (!pp.isEmpty() && !"v1".equals(pp) && !"v2".equals(pp)) {
            LOG.warn(L10n.tr("config.badProxyProtocol", pp));
            pp = "";
        }
        serveProxyProtocol = pp;
        serverPreauth = cfg.getBoolean("preauth", "server", true,
                L10n.tr("cfg.server.preauth"));
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
        prefetchRefreshSeconds = cfg.getInt("prefetchRefreshSeconds", "client",
                600, 30, 86_400, L10n.tr("cfg.client.prefetchRefreshSeconds"));

        cfg.setCategoryComment("telemetry", L10n.tr("cfg.telemetry.category"));
        telemetryEnhanced = cfg.getBoolean("enhanced", "telemetry", true,
                L10n.tr("cfg.telemetry.enhanced"));
        boolean masterEnabled = !cfg.hasKey("telemetry", "enable")
                || cfg.getBoolean("enable", "telemetry", true, "");
        telemetryEnabled = masterEnabled || telemetryEnhanced;

        // 解析失败时绝不能回写：会用默认值覆盖服主手里只是语法有瑕疵的文件
        if (loadedOk && file != null && cfg.hasChanged()) {
            try {
                cfg.save(file);
            } catch (java.io.IOException e) {
                LOG.warn(L10n.tr("config.applyFailed", file), e);
            }
        }
    }

    // ---- general ----

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
        if (Credentials.BACKEND_FRP_XTCP.equals(backendId)) {
            for (String key : params.keySet()) {
                if (!Credentials.frpXtcpParamKeys().contains(key)) {
                    LOG.warn(L10n.tr("config.unknownParamKey",
                            key, Credentials.frpXtcpParamKeys()));
                }
            }
        } else if (Credentials.BACKEND_GONC_P2P.equals(backendId)) {
            for (String key : params.keySet()) {
                if (!Credentials.goncP2pParamKeys().contains(key)) {
                    LOG.warn(L10n.tr("config.unknownParamKey",
                            key, Credentials.goncP2pParamKeys()));
                }
            }
        }
        return params;
    }

    /** 由配置组装凭证；配置不完整返回 null。报错信息只含键名不含值。 */
    public Credentials serverCredentials() {
        try {
            Map<String, String> p = serverParams;
            if (serverRendezvous) {
                // 内嵌会合点模式下地址由客户端自己补，见 CLAUDE.md 对应一节
                p = new LinkedHashMap<String, String>(p);
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

    public boolean serverPreauth() {
        return serverPreauth;
    }

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

    public String[] prefetchServers() {
        return prefetchServers.clone();
    }

    public Timings clientTimings() {
        return new Timings(clientPunchTimeoutSeconds * 1000L,
                probeIntervalMs, probeTimeoutMs, startupGraceMs)
                .withWarmupRetry(warmupRetryInitialSeconds * 1000L,
                        warmupRetryMaxSeconds * 1000L)
                .withPrefetchTimeout(prefetchTimeoutSeconds * 1000L)
                .withPrefetchRefresh(prefetchRefreshSeconds * 1000L);
    }

    public boolean telemetryEnabled() {
        return telemetryEnabled;
    }

    public boolean telemetryEnhanced() {
        return telemetryEnhanced;
    }
}
