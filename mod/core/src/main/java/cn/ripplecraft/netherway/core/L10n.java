package cn.ripplecraft.netherway.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 控制台与玩家提示文案的消息目录：所有用户可见文本集中于此，按语言取用。
 *
 * <p>目录直接写在代码里而不是资源文件：core 用裸 javac 编译并以源码形式
 * 编入平台 jar，资源文件在两条构建路径上都要额外接线；代码目录还让
 * en/zh 并排出现在同一处，改一条文案不会漏掉另一种语言。
 *
 * <p>与 {@link Json} 手写解析同一条理由：零依赖。占位符只支持
 * {@code {0}}–{@code {9}}，不用 {@code MessageFormat}——它对单引号与
 * 数字本地化（把毫秒数写成 1,234）的处理在这里全是坑。
 *
 * <p>语言选择：平台层在启动时用 {@link #use}（客户端取 Minecraft 语言
 * 设置，服务端取配置/系统 locale）；没人设置时按系统 locale 兜底。
 * Go agent 的输出语言经 {@code NETHERWAY_LANG} 环境变量同步
 * （{@link AgentProcess}、{@code ServeCommand} 启动子进程时写入）。
 *
 * <p>目录条目的 en/zh 占位符集合必须一致，SelfTest 逐 key 校验。
 */
public final class L10n {

    /** 支持的语言。zh_TW 等中文变体一并落到 ZH；其余全部落到 EN。 */
    public enum Language {
        EN("en"), ZH("zh");

        /** 传给 agent 的 NETHERWAY_LANG 值。 */
        public final String tag;

        Language(String tag) {
            this.tag = tag;
        }
    }

    private static final Map<String, String[]> CATALOG = new HashMap<String, String[]>();

    private static volatile Language current = detect(null);

    private L10n() {
    }

    /**
     * 切换语言。接受 Minecraft 语言码（{@code zh_CN}/{@code en_US}）、
     * 简码（{@code zh}/{@code en}）或 {@code auto}/空（跟随系统 locale）。
     */
    public static void use(String code) {
        current = detect(code);
    }

    public static Language language() {
        return current;
    }

    /** 取指定 key 的当前语言文案并填充 {@code {0}}–{@code {9}} 占位符。 */
    public static String tr(String key, Object... args) {
        String[] entry = CATALOG.get(key);
        if (entry == null) {
            // 缺 key 兜底：绝不抛异常——文案问题不能影响任何流程。
            // 返回 key 加参数，日志里仍看得出发生了什么。
            StringBuilder sb = new StringBuilder(key);
            if (args != null) {
                for (Object a : args) {
                    sb.append(' ').append(a);
                }
            }
            return sb.toString();
        }
        return format(entry[current.ordinal()], args);
    }

    private static Language detect(String code) {
        String c = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (c.isEmpty() || "auto".equals(c)) {
            c = Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT);
        }
        return c.startsWith("zh") ? Language.ZH : Language.EN;
    }

    private static String format(String pattern, Object[] args) {
        if (args == null || args.length == 0 || pattern.indexOf('{') < 0) {
            return pattern;
        }
        StringBuilder sb = new StringBuilder(pattern.length() + 16);
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '{' && i + 2 < pattern.length()
                    && pattern.charAt(i + 2) == '}'
                    && pattern.charAt(i + 1) >= '0' && pattern.charAt(i + 1) <= '9') {
                int idx = pattern.charAt(i + 1) - '0';
                if (idx < args.length) {
                    sb.append(args[idx]);
                } else {
                    sb.append(pattern, i, i + 3);
                }
                i += 3;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    // ---------- 供 SelfTest 校验目录一致性 ----------

    static Set<String> keys() {
        return CATALOG.keySet();
    }

    static String pattern(String key, Language lang) {
        String[] entry = CATALOG.get(key);
        return entry == null ? null : entry[lang.ordinal()];
    }

    private static void def(String key, String en, String zh) {
        if (CATALOG.put(key, new String[] {en, zh}) != null) {
            // 静态初始化期即失败：重复 key 是确定性错误，SelfTest 必然先撞上
            throw new IllegalStateException("duplicate L10n key: " + key);
        }
    }

    static {
        defChat();
        defUpgrade();
        defWarmup();
        defPrefetch();
        defAgent();
        defPreauth();
        defCredentials();
        defProxyProtocol();
        defForgeClient();
        defSniffer();
        defBukkit();
        defForgeServer();
        defConfig();
        defCfgComments();
        defTelemetry();
    }

    /** 玩家聊天栏可见的提示（经 notifyPlayer）。 */
    private static void defChat() {
        def("chat.prefix",
                "[Direct] ",
                "[直连] ");
        def("chat.switching.rtt",
                "Direct connection established ({0}ms), switching…",
                "已建立直连（延迟 {0}ms），正在切换…");
        def("chat.switching",
                "Direct connection established, switching…",
                "已建立直连，正在切换…");
        def("chat.switching.warm.rtt",
                "Direct connection established ({0}ms, warmed up), switching…",
                "已建立直连（延迟 {0}ms，预热），正在切换…");
        def("chat.switching.warm",
                "Direct connection established (warmed up), switching…",
                "已建立直连（预热），正在切换…");
        def("chat.rescue.rtt",
                "Background direct connection is ready ({0}ms), switching…",
                "后台直连已就绪（延迟 {0}ms），正在切换…");
        def("chat.rescue",
                "Background direct connection is ready, switching…",
                "后台直连已就绪，正在切换…");
        def("chat.adopted.rtt",
                "Joined the server over a warmed-up direct connection ({0}ms)",
                "已通过预热直连进入服务器（延迟 {0}ms）");
        def("chat.adopted",
                "Joined the server over a warmed-up direct connection",
                "已通过预热直连进入服务器");
    }

    private static void defUpgrade() {
        def("upgrade.dupActive",
                "Already handling the direct connection for room {0}; duplicate credentials ignored",
                "已在处理房间 {0} 的直连，忽略重复凭证");
        def("upgrade.dupGaveUp",
                "Direct connection for room {0} was given up this session; duplicate credentials ignored",
                "本次会话已放弃房间 {0} 的直连，忽略重复凭证");
        def("upgrade.ignoreState",
                "Current state is {0}; these credentials are ignored",
                "当前状态为 {0}，忽略本次凭证");
        def("upgrade.staleCold",
                "Stale upgrade ignored: not starting a cold agent after reset",
                "忽略过期升级：复位后不再启动冷 agent");
        def("upgrade.preparing",
                "Preparing direct connection: platform {0}, room {1} ({2})",
                "准备直连：平台 {0}，房间 {1}（{2}）");
        def("upgrade.credReceived",
                "Received credentials: {0}",
                "收到的凭证: {0}");
        def("upgrade.extractFailed",
                "Failed to extract the agent binary",
                "释放 agent 二进制失败");
        def("upgrade.agentBinary",
                "Agent binary: {0}",
                "agent 二进制: {0}");
        def("upgrade.agentCommand",
                "Starting agent: {0}",
                "启动 agent: {0}");
        def("upgrade.agentEvent",
                "Agent event: {0}",
                "agent 事件: {0}");
        def("upgrade.startFailed",
                "Failed to start the agent",
                "启动 agent 失败");
        def("upgrade.staleAttach",
                "Stale upgrade ignored: not adopting the freshly started agent after reset",
                "忽略过期升级：复位后不再接管刚启动的 agent");
        def("upgrade.waitingOutcome",
                "Waiting for the punch outcome, up to {0}ms (agent log: {1})",
                "等待打洞结果，最多 {0}ms（agent 详细日志: {1}）");
        def("upgrade.staleSwitch",
                "Stale upgrade ignored: not switching after reset (room {0})",
                "忽略过期升级：复位后不再切换（房间 {0}）");
        def("upgrade.ready",
                "Direct connection ready: port {0}, latency {1}ms, took {2}ms",
                "直连就绪，端口 {0}，延迟 {1}ms，用时 {2}ms");
        def("upgrade.staleReuse",
                "Stale upgrade ignored: not reusing the warm tunnel after reset",
                "忽略过期升级：复位后不再复用预热隧道");
        def("upgrade.reuseWarm",
                "Reusing the warm tunnel: port {0}, latency {1}ms, switching",
                "复用预热隧道，端口 {0}，延迟 {1}ms，正在切换");
        def("upgrade.rescueExhausted",
                "Auto-switch quota for room {0} is used up this session; waiting for the player to reconnect",
                "房间 {0} 本会话的自动切换次数已用完，改为等待玩家自行重连");
        def("upgrade.rescueSwitch",
                "Warm direct connection became ready mid-game; switching off the relay (room {0}, port {1})",
                "游玩中预热直连就绪，从中转切换（房间 {0}，端口 {1}）");
        def("upgrade.adoptRejected",
                "Current state is {0}; not adopting the direct-entry connection",
                "当前状态为 {0}，不采认直连条目的连接");
        def("upgrade.adopted",
                "This connection came through a direct entry (room {0}, port {1}); treated as upgraded",
                "本次连接经直连条目建立（房间 {0}，端口 {1}），视作已升级");
        def("upgrade.noRendezvousAddr",
                "Credentials carry no rendezvous address and the current server address cannot be derived; "
                        + "the direct connection will most likely fail",
                "凭证未带会合点地址，而当前连接的服务器地址也推导不出来，直连多半会失败");
        def("upgrade.noOrigin",
                "Cannot determine the Minecraft entry for these credentials; skipping the multi-server cache this time",
                "无法确定凭证的 Minecraft 入口，本次不写多服务缓存");
        def("upgrade.fillRendezvous",
                "Credentials carry no rendezvous address; filled in from the current connection as {0}",
                "凭证未带会合点地址，按当前连接补为 {0}");
        def("upgrade.skipCache",
                "Credentials lack a rendezvous address or Minecraft entry; "
                        + "not writing the multi-server cache (keeping the existing copy)",
                "凭证缺会合点地址或 Minecraft 入口，不写入多服务缓存（保留已有版本）");
        def("upgrade.cached",
                "Credentials cached; next launch will warm up the direct connection for room {0}",
                "凭证已缓存，下次启动将预热房间 {0} 的直连");
        def("upgrade.cacheFailed",
                "Failed to cache the credentials (only affects warm-up on next launch): {0}",
                "缓存凭证失败（只影响下次启动的预热）: {0}");
        def("upgrade.staleGiveUp",
                "Stale give-up ignored ({0})",
                "忽略过期升级的放弃（{0}）");
        def("upgrade.gaveUp",
                "Giving up on the direct connection; staying on the current route ({0})",
                "放弃直连，继续使用当前线路（{0}）");
        def("upgrade.reportSent",
                "Upgrade result reported back: {0}",
                "已回传直连结果: {0}");
        def("upgrade.reportFailed",
                "Failed to report the upgrade result: {0}",
                "回传直连结果失败: {0}");
        def("upgrade.staleRedirect",
                "Stale redirect ignored: the upgrade was reset after the task was queued",
                "忽略过期重定向：任务排队后升级已复位");
        def("upgrade.redirectFailed",
                "Failed to start the direct-connection switch",
                "发起直连切换失败");
        def("upgrade.failed",
                "Failed to establish the direct connection",
                "建立直连失败");
        def("reason.outcomeTimeout",
                "Timed out waiting for the direct-connection outcome",
                "等待直连结果超时");
        def("reason.punchFailed",
                "Hole punching did not succeed",
                "打洞未成功");
        def("reason.platformUnsupported",
                "Direct connection is not supported on this system: {0}",
                "当前系统不支持直连: {0}");
        def("reason.interrupted",
                "The upgrade was interrupted",
                "升级过程被中断");
    }

    private static void defWarmup() {
        def("warmup.platformUnsupported",
                "Direct connection is not supported on this system; skipping warm-up: {0}",
                "当前系统不支持直连，跳过预热: {0}");
        def("warmup.extractFailed",
                "Failed to extract the agent binary; warm-up is unavailable this session (normal play is unaffected)",
                "释放 agent 二进制失败，本会话预热不可用（不影响正常游戏）");
        def("warmup.noCredentials",
                "No cached credentials and no servers to prefetch from; skipping warm-up",
                "没有缓存凭证，也没有可预取的服务器地址，跳过预热");
        def("warmup.idle",
                "No usable credentials yet; prefetch keeps running (normal connections are unaffected)",
                "暂无可用凭证，将继续预取（不影响普通连接）");
        def("warmup.loopError",
                "The warm-up manager hit an error this round (still running; normal play is unaffected)",
                "预热管理器一轮异常（继续运行，不影响正常游戏）");
        def("warmup.paramsUpdated",
                "Connection settings for room {0} changed; rebuilding the warm tunnel",
                "房间 {0} 的建联参数已更新，将重建预热隧道");
        def("warmup.tunnelExited",
                "Warm tunnel for room {0} exited; re-establishing",
                "房间 {0} 的预热隧道已退出，将重新建立");
        def("warmup.staleCacheNoAddress",
                "The old cache for room {0} has no server entry; cannot warm up "
                        + "(heals itself after a prefetch or one relay join)",
                "房间 {0} 的旧缓存未带服务入口，无法预热（预取或进服一次后自愈）");
        def("warmup.attemptError",
                "Warm-up attempt for room {0} failed with an error (will keep retrying)",
                "房间 {0} 的预热尝试异常（继续重试）");
        def("warmup.attempt",
                "Warming up a direct connection with credentials for room {0} ({1})",
                "用房间 {0} 的凭证预热直连（{1}）");
        def("warmup.agentCommand",
                "Starting warm-up agent: {0}",
                "启动预热 agent: {0}");
        def("warmup.agentEvent",
                "Warm-up agent event for room {0}: {1}",
                "房间 {0} 的预热 agent 事件: {1}");
        def("warmup.agentStderr",
                "Warm-up agent for room {0}: {1}",
                "房间 {0} 的预热 agent: {1}");
        def("warmup.ready",
                "Warm direct connection for room {0} is ready: port {1}, latency {2}ms",
                "房间 {0} 的预热直连就绪，端口 {1}，延迟 {2}ms");
        def("warmup.notReady",
                "Warm-up for room {0} did not succeed ({1}) — retrying with backoff; "
                        + "normal connections are unaffected",
                "房间 {0} 的预热未成功（{1}）——将按退避重试，不影响普通连接");
        def("reason.warmupOutcomeTimeout",
                "Timed out waiting for the punch outcome",
                "等待打洞结果超时");
        def("warmup.retryIn",
                "Warm-up for room {0} retries in {1}s",
                "房间 {0} 的预热将在 {1} 秒后重试");
        def("warmup.readyObserverError",
                "The warm-tunnel ready callback failed (the tunnel itself is unaffected)",
                "预热就绪回调异常（隧道不受影响）");
        def("warmup.degradedReported",
                "Agent reports the warm tunnel for room {0} keeps failing health checks",
                "agent 报告房间 {0} 的预热隧道持续自检失败");
        def("warmup.tunnelDegraded",
                "Warm tunnel for room {0} is no longer usable; rebuilding and refreshing credentials",
                "房间 {0} 的预热隧道已不可用，将重建并立即刷新凭证");
        def("warmup.degradedInUse",
                "Warm tunnel for room {0} reported unhealthy but is carrying the current connection; "
                        + "leaving it alone",
                "房间 {0} 的隧道上报失效，但正承载当前连接，暂不处理");
    }

    private static void defPrefetch() {
        def("prefetch.error",
                "Error while prefetching credentials from {0} (normal play is unaffected)",
                "向 {0} 预取凭证时出错（不影响正常游戏）");
        def("prefetch.rejected",
                "Prefetching credentials from {0} did not succeed: {1}",
                "向 {0} 预取凭证未成功: {1}");
        def("prefetch.fillRendezvous",
                "Prefetched credentials carry no rendezvous address; filled in from the source as {0}",
                "预取到的凭证未带会合点地址，按来源补为 {0}");
        def("prefetch.duplicate",
                "Candidate {0} points at the same credentials as an earlier entry; skipping the duplicate write",
                "候选 {0} 与前面的入口指向同一份凭证，跳过重复写入");
        def("prefetch.ok",
                "Prefetched credentials for room {1} from {0}",
                "已从 {0} 预取到房间 {1} 的凭证");
        def("prefetch.unchanged",
                "Credentials for room {1} from {0} are unchanged",
                "{0} 的房间 {1} 凭证未变化");
        def("prefetch.timeout",
                "Prefetching credentials from {0} timed out",
                "向 {0} 预取凭证超时");
        def("prefetch.cacheFailed",
                "Failed to write the credential cache for {0} (normal play is unaffected)",
                "写入 {0} 的凭证缓存失败（不影响正常游戏）");
        def("prefetch.noCredential",
                "no credentials returned",
                "未返回凭证");
    }

    private static void defAgent() {
        def("agent.earlyExit",
                "The agent exited without reporting an outcome",
                "agent 未给出结果即退出");
        def("agent.earlyExitTail",
                "The agent exited without reporting an outcome: {0}",
                "agent 未给出结果即退出：{0}");
        def("binary.missing",
                "The jar has no agent binary for this platform: {0} (platform {1})",
                "jar 内缺少当前平台的 agent: {0}（平台 {1}）");
        def("binary.missingWithFallback",
                "The jar has no agent binary for this platform: {0}, and the emulation fallback {1} "
                        + "is missing too (platform {2})",
                "jar 内缺少当前平台的 agent: {0}，转译兜底 {1} 也不存在（平台 {2}）");
        def("platform.unknownOs",
                "Unrecognized operating system: {0}",
                "无法识别的操作系统: {0}");
        def("platform.unknownArch",
                "Unsupported CPU architecture: {0}",
                "不支持的处理器架构: {0}");
    }

    private static void defPreauth() {
        def("preauth.closedEarly",
                "The server closed the connection before returning a preauth response "
                        + "(server.preauth may be off, or the entry does not forward to this server)",
                "服务器在返回预认证响应前关闭连接（可能未启用 server.preauth，或入口未转发到这台服务器）");
        def("preauth.payloadTooLarge",
                "Response payload exceeds the limit: {0}",
                "响应 payload 超限: {0}");
        def("preauth.versionMismatch",
                "Server protocol version {0} does not match local version {1}",
                "服务端协议版本 {0} 与本机 {1} 不符");
        def("preauth.noReason",
                "no reason given",
                "未说明");
        def("preauth.rejected",
                "The server refused: {0}",
                "服务端拒绝: {0}");
        def("preauth.incompleteConfig",
                "The server credential configuration is incomplete",
                "服务端凭证配置不完整");
        def("preauth.issued",
                "Preauth: {0} ({1}) obtained credentials for room {2}",
                "预下发: {0} ({1}) 取得房间 {2} 的凭证");
        def("preauth.buildFailed",
                "Failed to assemble the credentials",
                "组装凭证失败");
        def("preauth.notAFrame",
                "Not a netherway preauth frame",
                "不是 netherway 预认证帧");
        def("preauth.frameTooLarge",
                "Payload exceeds the {0}-byte limit",
                "payload 超过上限 {0} 字节");
        def("preauth.missingIdentity",
                "username and uuid must not be empty",
                "username 和 uuid 不能为空");
        def("preauth.badUsername",
                "Invalid username",
                "username 不合法");
        def("preauth.badUuid",
                "Invalid uuid (expect 32 hex chars, hyphens allowed)",
                "uuid 不合法（应为 32 位 hex，可带连字符）");
    }

    private static void defCredentials() {
        def("cred.paramsNull",
                "params must not be null",
                "params 不能为 null");
        def("cred.emptyParamKey",
                "Parameter keys must not be empty",
                "参数键不能为空");
        def("cred.paramKeyEquals",
                "Parameter keys must not contain '=': {0}",
                "参数键不能含等号: {0}");
        def("cred.paramValueNull",
                "Value of parameter {0} must not be null",
                "参数 {0} 的值不能为 null");
        def("cred.tooManyParams",
                "Too many parameters: {0}",
                "参数过多: {0}");
        def("cred.emptyField",
                "Credential field {0} must not be empty",
                "凭证字段 {0} 不能为空");
        def("cred.emptyData",
                "The credential payload is empty",
                "凭证数据为空");
        def("cred.versionTooOld",
                "Credential format version too old: {0}",
                "凭证格式版本过旧: {0}");
        def("cred.invalid",
                "Invalid credential payload: {0}",
                "凭证内容非法: {0}");
        def("report.emptyData",
                "The upgrade-report payload is empty",
                "结果回执数据为空");
        def("report.versionTooOld",
                "Upgrade-report version too old: {0}",
                "结果回执版本过旧: {0}");
    }

    private static void defProxyProtocol() {
        def("proxyproto.v2BadVersion",
                "v2: version after the signature is not 2",
                "v2 签名后的版本号不是 2");
        def("proxyproto.v2BadCommand",
                "v2: invalid command field",
                "v2 命令字段非法");
        def("proxyproto.v2BadFamily",
                "v2: invalid address family",
                "v2 地址族非法");
        def("proxyproto.v2TooLarge",
                "v2: extra data exceeds the accepted {0}-byte limit",
                "v2 附加数据超过接受上限 {0} 字节");
        def("proxyproto.v2ShortIpv4",
                "v2: truncated IPv4 address block",
                "v2 IPv4 地址块不完整");
        def("proxyproto.v2ShortIpv6",
                "v2: truncated IPv6 address block",
                "v2 IPv6 地址块不完整");
        def("proxyproto.v1NoCrlf",
                "v1: no CRLF within the first {0} bytes",
                "v1 头超过 {0} 字节仍未见 CRLF");
        def("proxyproto.v1TooLong",
                "v1: header too long",
                "v1 头超长");
        def("proxyproto.v1BadCrlf",
                "v1: CR not followed by LF",
                "v1 头的 CR 后不是 LF");
        def("proxyproto.v1BadFieldCount",
                "v1: header does not have 6 fields",
                "v1 头字段数不是 6");
        def("proxyproto.v1BadFamily",
                "v1: protocol family is not TCP4/TCP6/UNKNOWN",
                "v1 协议族不是 TCP4/TCP6/UNKNOWN");
        def("proxyproto.v1BadPort",
                "v1: invalid port",
                "v1 端口非法");
        def("proxyproto.v1BadAddress",
                "v1: address is not a valid IP literal",
                "v1 地址不是合法的 IP 字面量");
    }

    /** Forge 客户端半边（bridge、事件、预取接线、服务器列表）。 */
    private static void defForgeClient() {
        def("fbridge.taskFailed",
                "A main-thread task failed",
                "主线程任务执行失败");
        def("fbridge.redirectLanded",
                "New connection {0} is the direct-connection switch we started",
                "新连接 {0} 是我们发起的直连切换");
        def("fbridge.redirectMissed",
                "New connection {0} is unrelated to the direct-connection switch (expected loopback port {1})",
                "新连接 {0} 与直连切换无关（期望回环端口 {1}）");
        def("fbridge.redirectTimeout",
                "Timed out waiting for a connection on loopback port {0}",
                "等待回环端口 {0} 的连接超时");
        def("fbridge.connectTo",
                "Disconnecting and reconnecting to {0}:{1}",
                "断开当前连接，重连到 {0}:{1}");
        def("fbridge.parseServerFailed",
                "Failed to parse the current server address: {0}",
                "解析当前服务器地址失败: {0}");
        def("fbridge.noConnection",
                "No connection right now; the upgrade report was not sent",
                "当前没有连接，结果回执未发送");
        def("fclient.credPacket",
                "Received a credentials packet from the server ({0} bytes)",
                "收到服务端凭证包，{0} 字节");
        def("fclient.credDecodeFailed",
                "Failed to decode the credentials; this delivery is ignored",
                "凭证解码失败，忽略本次下发");
        def("fclient.switchDone",
                "Direct-connection switch complete; the agent keeps carrying the new connection",
                "直连切换完成，agent 继续承载新连接");
        def("fclient.adopted",
                "Player joined through a direct entry; the warm tunnel has been adopted",
                "玩家经直连条目进服，已采认预热隧道");
        def("fclient.disconnectBySwitch",
                "The disconnect was caused by the direct-connection switch; keeping the agent while the new connection lands",
                "断开由直连切换引发，保留 agent 等待新连接落地");
        def("fclient.staleDisconnect",
                "Ignoring a late disconnect event from a non-current connection",
                "忽略非当前连接的迟到断开事件");
        def("fclient.redirectCommitted",
                "Direct-connection switch committed; keeping the agent until the bridge starts the target connection",
                "直连切换已提交，保留 agent 等待 bridge 开始目标连接");
        def("fclient.playerLeft",
                "Player left the server; stopping the agent and resetting",
                "玩家离开服务器，停止 agent 并复位");
        def("fclient.noUsername",
                "The game session has no username; skipping credential prefetch",
                "游戏会话没有用户名，跳过凭证预取");
        def("fclient.noPrefetchTargets.detail",
                "No server addresses to prefetch from (client.prefetchServers is empty and the server list "
                        + "has no usable entries); skipping credential prefetch",
                "没有可预取的服务器地址（client.prefetchServers 为空且服务器列表无可用条目），跳过凭证预取");
        def("fclient.noPrefetchTargets",
                "No server addresses to prefetch from; skipping credential prefetch",
                "没有可预取的服务器地址，跳过凭证预取");
        def("fclient.prefetchCandidates",
                "Prefetch candidates (bounded parallelism): {0}",
                "预取候选（有界并行）: {0}");
        def("fclient.serverListReadFailed",
                "Failed to read the server list; skipping the server.dat scan",
                "读取服务器列表失败，跳过 server.dat 扫描");
        def("direntry.upToDate",
                "The direct entry is already up to date: {0} → {1}",
                "直连条目已是最新: {0} → {1}");
        def("direntry.updated",
                "Updated the direct entry in the server list: {0} → {1}",
                "已更新服务器列表的直连条目: {0} → {1}");
        def("direntry.migrated",
                "Upgraded a legacy direct entry to: {0} → {1}",
                "已将旧版直连条目升级为: {0} → {1}");
        def("direntry.added",
                "Added a direct entry to the server list: {0} → {1}",
                "已在服务器列表添加直连条目: {0} → {1}");
        def("router.overridden",
                "Server entry overridden for this session: {0} → {1}",
                "已在本次会话覆盖服务器入口: {0} → {1}");
        def("router.restored",
                "The warm tunnel is gone; server entry restored to the original route: {0}",
                "预热隧道已失效，服务器入口恢复原线路: {0}");
        def("routegui.takeoverFailed",
                "Could not take over the vanilla server list; using the original entry this time",
                "无法接管原版服务器列表，本次仍使用原入口");
        def("routegui.readSelectionFailed",
                "Failed to read the selected server; using the original entry",
                "读取选中的服务器失败，改用原入口");
        def("routegui.resolved",
                "Server-list selection resolved to a warm tunnel: {0} → {1}",
                "服务器列表选择已解析到预热隧道: {0} → {1}");
        def("routegui.pingResolved",
                "Server-list latency probe resolved to a warm tunnel: {0} → {1}",
                "服务器列表延迟探测已解析到预热隧道: {0} → {1}");
        def("routegui.repingBroken",
                "Failed to read the server list; latency readings after route changes now update on manual refresh only: {0}",
                "读取服务器列表失败，路由变化后的延迟读数改由手动刷新更新: {0}");
        def("routegui.copyMetaFailed",
                "Failed to copy FML server-list metadata: {0}",
                "复制 FML 服务器列表元数据失败: {0}");
    }

    /** 服务端接入链嗅探器（预认证、PROXY 剥头、会合点转发）。 */
    private static void defSniffer() {
        def("sniffer.noEndpoint",
                "No listening endpoint to hook; neither preauth nor PROXY header stripping is active",
                "没有找到可挂载的监听端点，预认证与 PROXY 剥头均未生效");
        def("sniffer.preauthHooked",
                "Preauth hooked on {0} listening endpoint(s): players can obtain direct-connection credentials "
                        + "on the MC port before joining (no extra listening port)",
                "预认证已挂载到 {0} 个监听端点：玩家可在进服前于 MC 端口上换取直连凭证（不另开监听端口）");
        def("sniffer.proxyHooked",
                "PROXY protocol stripping hooked on {0} listening endpoint(s); tunneled connections will show "
                        + "their real source address",
                "PROXY protocol 剥头已挂载到 {0} 个监听端点，经隧道进来的连接将以真实来源地址示人");
        def("sniffer.installFailed",
                "Failed to install the connection sniffer (unexpected MC internals?). "
                        + "If serve runs with -proxy-protocol, turn that off first or players cannot connect",
                "嗅探器挂载失败（MC 内部结构与预期不符？）。serve 侧若开着 -proxy-protocol 请先关掉，否则玩家会连不上");
        def("sniffer.rendezvousDialFailed",
                "Failed to connect to the embedded rendezvous at 127.0.0.1:{0}; this player's tunnel cannot be "
                        + "established (is the built-in serve running?): {1}",
                "连接内嵌会合点 127.0.0.1:{0} 失败，玩家这条隧道建不起来（内置 serve 没起来？）: {1}");
        def("sniffer.badPreauthFrame",
                "Invalid preauth frame; disconnecting: {0}",
                "预认证帧非法，断开: {0}");
        def("sniffer.versionMismatch",
                "Protocol version mismatch (server {0})",
                "协议版本不符（服务端 {0}）");
        def("sniffer.unknownOp",
                "Unknown operation {0}",
                "未知操作 {0}");
        def("sniffer.badRequest",
                "Malformed request",
                "请求内容无法解析");
        def("sniffer.preauthError",
                "Preauth handling failed",
                "预认证处理异常");
        def("sniffer.internalError",
                "Internal server error",
                "服务端内部错误");
        def("sniffer.badProxyHeader",
                "Connection from {0} carried an invalid PROXY header ({1}); disconnected",
                "来自 {0} 的连接带着非法的 PROXY 头（{1}），已断开");
        def("sniffer.proxyStripped",
                "PROXY header stripped; the connection's real source is {0}",
                "PROXY 头已剥离，连接真实来源 {0}");
        def("sniffer.noPacketHandler",
                "No packet_handler in the pipeline; real source {0} could not be written back (header stripped only)",
                "pipeline 里没有 packet_handler，真实来源 {0} 未能写回（仅剥头）");
        def("sniffer.relayError",
                "Connection to the embedded rendezvous failed: {0}",
                "与内嵌会合点之间的连接异常: {0}");
        def("sniffer.exclusiveError",
                "Connection error while in exclusive mode ({0}); disconnecting: {1}",
                "独占中的连接异常（{0}），断开: {1}");
    }

    /** Bukkit/Spigot/Paper plugin platform layer. */
    private static void defBukkit() {
        def("bukkit.injected",
                "Server network pipeline hooked ({0} listen channel(s))",
                "已挂接服务端网络管线（{0} 个监听通道）");
        def("bukkit.injectFailed",
                "Failed to hook the server network pipeline; preauth, the embedded rendezvous and PROXY "
                        + "header stripping are unavailable (credential delivery after login still works): {0}",
                "服务端网络管线挂接失败，预认证、内嵌会合点与 PROXY 剥头均不可用（登录后下发凭证不受影响）: {0}");
    }

    /** Forge 服务端半边（入口、下发、回执、内置 serve）。 */
    private static void defForgeServer() {
        def("fserver.noPort",
                "Cannot determine the Minecraft listening port; the built-in serve was not started "
                        + "(set server.localPort explicitly)",
                "无法确定 Minecraft 监听端口，内置 serve 未启动（可用 server.localPort 显式指定）");
        def("fserver.rendezvousPort",
                "The embedded rendezvous will listen on 127.0.0.1:{0}: players' frp control connections are "
                        + "forwarded in from the Minecraft port, so the public side only needs a TCP tunnel "
                        + "to the Minecraft port",
                "内嵌会合点将监听 127.0.0.1:{0}：玩家的 frp 控制连接会从 Minecraft "
                        + "端口转发进去，公网侧只需要一条能到 Minecraft 端口的 TCP 隧道");
        def("fserver.rendezvousPortFailed",
                "Failed to pick a rendezvous port; the embedded rendezvous is disabled",
                "挑选会合点端口失败，内嵌会合点未启用");
        def("fserver.preauthOn",
                "Preauth is on: players can obtain direct-connection credentials on the MC port before joining "
                        + "(no identity check)",
                "预认证已开启：玩家可在进服前于 MC 端口换取直连凭证（不做身份验证）");
        def("fserver.incompleteCred",
                "server.enabled is on but the credential configuration is incomplete; no direct-connection "
                        + "credentials will be delivered (check server.params)",
                "server.enabled 已开启但凭证配置不完整，不会下发直连凭证（检查 server.params）");
        def("fserver.incompleteCredKeys",
                "server.enabled is on but the credential configuration is incomplete; no direct-connection "
                        + "credentials will be delivered (check keys such as room in server.params)",
                "server.enabled 已开启但凭证配置不完整，不会下发直连凭证（检查 server.params 里的 room 等键）");
        def("fserver.enabled",
                "Server-side direct connection enabled; {0} will be delivered after players log in",
                "服务端直连已启用，玩家登录后将下发 {0}");
        def("fserver.tokenIssuing",
                "Per-player token issuing enabled (valid for {0} days), signing key fingerprint {1}",
                "每玩家令牌签发已启用（有效期 {0} 天），签发密钥指纹 {1}");
        def("fserver.willRunServe",
                "The built-in serve starts with the server and registers room \"{0}\" with frps "
                        + "(if a standalone netherway serve also runs on this host, stop one of them: "
                        + "same-name proxies conflict on registration)",
                "将随服务端启动内置 serve，把房间 \"{0}\" 注册到 frps"
                        + "（若宿主机还单独跑着 netherway serve，请停掉其一，同名代理会注册冲突）");
        def("fserver.noRunAgent",
                "Note: server.runAgent is off, so the mod only delivers credentials; the proxy for room \"{0}\" "
                        + "must be registered with frps by a standalone netherway serve on the host "
                        + "(-room must match), otherwise players will see \"xtcp server doesn't exist\"",
                "注意：server.runAgent 已关闭，mod 只下发凭证；房间 \"{0}\" 的"
                        + "代理需要宿主机上独立运行的 netherway serve 注册到 frps"
                        + "（-room 必须一致），否则玩家侧会报 xtcp server doesn't exist");
        def("fserver.delivered",
                "Delivered direct-connection credentials to {0}: {1}",
                "已向 {0} 下发直连凭证 {1}");
        def("fserver.reportUndecodable",
                "The upgrade report from {0} could not be decoded; ignored",
                "来自 {0} 的结果回执无法解码，已忽略");
        def("fserver.reportUpgraded",
                "{0} switched to a direct connection (room {1}, latency {2}ms, setup {3}ms)",
                "{0} 已切换为直连（房间 {1}，延迟 {2}ms，建链 {3}ms）");
        def("fserver.reportGaveUp",
                "{0} failed to go direct (room {1}): {2}",
                "{0} 直连失败（房间 {1}）：{2}");
        def("serve.backendUnsupported",
                "The built-in serve currently supports only frp-xtcp (configured backend: {0}); "
                        + "run the matching tunnel service on the host yourself",
                "内置 serve 目前仅支持 frp-xtcp（当前 backend: {0}），请在宿主机上自行运行对应的隧道服务");
        def("serve.noBinary",
                "No bundled agent binary for this system; cannot start serve: {0}",
                "当前系统没有内置的 agent 二进制，无法启动 serve: {0}");
        def("serve.extractFailed",
                "Failed to extract the agent binary for serve",
                "释放 serve 的 agent 二进制失败");
        def("serve.starting",
                "Starting the built-in serve (platform {0}): {1}",
                "启动内置 serve（平台 {0}）: {1}");
        def("serve.startFailed",
                "Failed to start the built-in serve",
                "内置 serve 启动失败");
        def("serve.exited",
                "The built-in serve exited (code {0}). frp reconnects on its own after network drops, so an "
                        + "outright exit usually means a configuration error (frps address/token/secret); see the "
                        + "[serve] log above for the cause, fix the configuration and restart the server",
                "内置 serve 进程退出（码 {0}）。frp 掉线会自动重连，进程直接退出"
                        + "通常是配置错误（frps 地址/令牌/密钥），原因见上方 [serve] 日志；"
                        + "修正配置后重启服务端生效");
        def("serve.stopped",
                "The built-in serve has stopped",
                "内置 serve 已停止");
    }

    private static void defConfig() {
        def("config.applyFailed",
                "Failed to apply the configuration; server features are off and the client runs with defaults "
                        + "this time: {0}",
                "配置应用失败，本次服务端功能关闭、客户端使用默认值: {0}");
        def("config.parseFailed",
                "Failed to parse the configuration file; running with defaults this time "
                        + "(fix the syntax and restart): {0}",
                "配置文件解析失败，本次以默认值运行（改好语法后重启生效）: {0}");
        def("config.rendezvousNeedsRunAgent",
                "server.rendezvous requires server.runAgent=true (the rendezvous lives inside the built-in "
                        + "serve process; a standalone serve does not open it). Treated as disabled this time",
                "server.rendezvous 需要 server.runAgent=true（会合点起在内置 serve "
                        + "进程里，独立运行的 serve 不会开它）。本次按未启用处理");
        def("config.secretAutoNeedsRunAgent",
                "secret=auto requires server.runAgent=true (the built-in serve and the delivered credentials "
                        + "share the same source); a standalone serve cannot learn the generated secret and "
                        + "players would fail to punch through forever",
                "secret=auto 需要 server.runAgent=true（内置 serve 与下发凭证同源）；"
                        + "独立运行的 serve 无法得知本次生成的密钥，玩家会一直打洞失败");
        def("config.secretAutoGenerated",
                "secret=auto: a random room secret was generated for this run; it rotates on every server "
                        + "restart and players need to do nothing",
                "secret=auto：本次启动已生成随机房间密钥，服务端每次重启轮换，玩家侧无需任何操作");
        def("config.tokenAutoGenerated",
                "token=auto: a random rendezvous token was generated for this run; it rotates on every server "
                        + "restart and players need to do nothing",
                "token=auto：本次启动已生成随机会合点令牌，服务端每次重启轮换，玩家侧无需任何操作");
        def("config.tokenAutoClassic",
                "token=auto only makes sense with server.rendezvous=true (in classic mode the token must match "
                        + "the public frps auth.token). Using the literal value \"auto\", which is almost "
                        + "certainly not what you want",
                "token=auto 只在 server.rendezvous=true 时有意义（经典模式的 token "
                        + "必须与公网 frps 的 auth.token 一致）。已按字面值 \"auto\" 使用，"
                        + "这几乎肯定不是你想要的");
        def("config.sessionKeyAutoNeedsRunAgent",
                "sessionKey=auto generates a fresh key on every start, but server.runAgent=false means the "
                        + "externally-run serve cannot know it; players will never be able to punch",
                "sessionKey=auto 每次启动生成新密钥，但 server.runAgent=false 时外部独立运行的 "
                        + "serve 拿不到这个值，玩家将永远打不通");
        def("config.sessionKeyAutoGenerated",
                "sessionKey=auto: a random session key was generated for this run; it rotates on every server "
                        + "restart and players need to do nothing",
                "sessionKey=auto：本次启动已生成随机会话密钥，服务端每次重启轮换，玩家侧无需任何操作");
        def("config.rendezvousFrpOnly",
                "server.rendezvous only applies to the frp-xtcp backend; backend \"{0}\" needs no rendezvous "
                        + "(MQTT signaling), treated as off",
                "server.rendezvous 只适用于 frp-xtcp backend；backend \"{0}\" 不需要会合点"
                        + "（MQTT 信令），已按关闭处理");
        def("config.tokenSigningFrpOnly",
                "tokenSigningKey only applies to the frp-xtcp backend (there is no login gate to verify "
                        + "per-player tokens under backend \"{0}\"); per-player tokens disabled",
                "tokenSigningKey 只适用于 frp-xtcp backend（backend \"{0}\" 下没有校验每玩家令牌"
                        + "的登录关卡）；已停用每玩家令牌");
        def("config.badProxyProtocol",
                "server.proxyProtocol only accepts v1 or v2 (current value \"{0}\"); treated as off",
                "server.proxyProtocol 只接受 v1 或 v2（当前值 \"{0}\"），已按关闭处理");
        def("config.paramNotKv",
                "A line in server.params is not of the key=value form; that line is ignored",
                "server.params 中的行不是 key=value 形式，已忽略一行");
        def("config.unknownParamKey",
                "Key \"{0}\" in server.params is not part of the frp-xtcp contract (known keys: {1}); "
                        + "the agent will ignore it — fix it if it is a typo",
                "server.params 里的键 \"{0}\" 不在 frp-xtcp 的契约里（认识的键: {1}），"
                        + "agent 会忽略它；若是拼写错误请改正");
        def("config.incompleteCred",
                "The credential configuration is incomplete: {0}",
                "凭证配置不完整: {0}");
    }

    /**
     * Forge cfg 文件的注释（ModConfig）。与运行期日志不同：这些文本在 cfg
     * 首次生成时按当时的 general.language 写死进文件，此后不随语言热切换；
     * 只改这里的文案不会触发 Forge 回写已有 cfg（ModConfigSelfTest 钉住），
     * 文件里的注释要等其它变更导致回写时才换语言。general.language 自身的
     * 注释保持双语、不进目录——语言还没选出来时它也得读得懂。
     */
    private static void defCfgComments() {
        def("cfg.server.category",
                "Server side only. A freshly generated config is already the recommended embedded-rendezvous "
                        + "mode and usually needs no editing:\n"
                        + "any public TCP address players use just has to forward to the Minecraft port.\n"
                        + "See the advanced configuration in the README when self-hosting frps or switching "
                        + "the tunnel backend.\n"
                        + "Note: this file holds auto-generated secrets; restrict its permissions to the "
                        + "server process.\n"
                        + "Clients do not need to fill in the server category.",
                "服务端专用。新生成的配置就是推荐的内嵌会合点模式，通常无需修改：\n"
                        + "只要玩家正在使用的公网 TCP 地址能转发到 Minecraft 端口即可。\n"
                        + "需要自建 frps 或更换隧道 backend 时再看 README 的高级配置。\n"
                        + "注意：此文件含自动生成密钥的配置，权限只给服务端进程；\n"
                        + "客户端不需要填写 server 类目。");
        def("cfg.server.enabled",
                "Master switch for server-side direct connections. On by default; set to false to disable "
                        + "entirely",
                "服务端直连总开关。默认开启；设为 false 可完全关闭");
        def("cfg.server.runAgent",
                "Start the built-in serve together with the server. Must stay true in the default mode.\n"
                        + "Set to false only when a standalone netherway serve already runs on the host, "
                        + "or the hosting environment forbids child processes",
                "随服务端启动内置 serve。默认模式必须保持 true。\n"
                        + "已在宿主机单独运行 netherway serve、或托管环境禁止启动子进程时设为 false");
        def("cfg.server.localPort",
                "Local Minecraft port the built-in serve publishes; 0 means the port the server actually "
                        + "listens on",
                "内置 serve 发布的 Minecraft 本地端口，0 表示使用服务器实际监听的端口");
        def("cfg.server.backend",
                "Tunnel backend identifier: frp-xtcp (default; embedded rendezvous, per-player tokens) or "
                        + "gonc-p2p (MQTT signaling, no rendezvous; params take sessionKey=auto plus room)",
                "隧道方案标识：frp-xtcp（默认；内嵌会合点、每玩家令牌）或 "
                        + "gonc-p2p（MQTT 信令、无会合点；params 填 sessionKey=auto 与 room 即可）");
        def("cfg.server.rendezvous",
                "Recommended and default mode: run the rendezvous inside the server process.\n"
                        + "Players' control connections come in through the public Minecraft entry; "
                        + "no self-hosted frps or authplugin needed.\n"
                        + "Keep runAgent=true; server.params already carries working out-of-the-box values.\n"
                        + "Set to false only when switching to a self-hosted frps, and replace the whole "
                        + "params list as the README describes",
                "推荐且默认模式：在服务端进程内运行会合点。\n"
                        + "玩家的控制连接从 Minecraft 公网入口进入，无需自建 frps 或部署 authplugin。\n"
                        + "保持 runAgent=true；server.params 已带齐开箱即用的参数。\n"
                        + "只有改用自建 frps 时才设为 false，并按 README 替换整个 params 列表");
        def("cfg.server.params",
                "Tunnel parameters, one key=value per line. The three defaults already work.\n"
                        + "Replace the whole list per the README only when self-hosting frps; "
                        + "advanced auth options do not go here",
                "隧道参数，每行一个 key=value。默认三项已经可用。\n"
                        + "自建 frps 时才按 README 替换整个列表；高级鉴权项不写在这里");
        def("cfg.server.params.defaultNote",
                "Parameters for the default embedded rendezvous; leave them as they are",
                "默认内嵌会合点所需参数，保持原样即可");
        def("cfg.server.punchTimeoutSeconds",
                "Suggested hole-punching timeout in seconds for clients; 0 lets each client use its "
                        + "own setting",
                "建议客户端使用的打洞超时秒数，0 表示由客户端自己配置");
        def("cfg.server.tokenSigningKey",
                "[Advanced auth] Signing key for per-player tokens. Works as-is with the embedded "
                        + "rendezvous;\n"
                        + "with a self-hosted frps it must match the authplugin -key flag.\n"
                        + "This is not the rendezvous token in server.params; see the README for deployment",
                "【高级鉴权项】每玩家令牌签发密钥。内嵌会合点可直接使用；\n"
                        + "自建 frps 时须与 authplugin 的 -key 一致。\n"
                        + "它不是 server.params 中的会合点令牌；具体部署见 README");
        def("cfg.server.tokenTtlDays",
                "[Advanced auth] Validity of per-player tokens in days; renewed automatically on "
                        + "every login",
                "【高级鉴权项】每玩家令牌的有效天数；每次登录自动续签");
        def("cfg.server.serveAuthToken",
                "[Advanced, self-hosted frps] Static identity token of the built-in serve;\n"
                        + "must match the authplugin -static-token flag and must never go into server.params",
                "【自建 frps 高级项】内置 serve 的静态身份令牌，\n"
                        + "须与 authplugin 的 -static-token 一致，绝不能放进 server.params");
        def("cfg.server.proxyProtocol",
                "Have the tunnel process send a PROXY protocol header before dialing the local MC port "
                        + "(v1 or v2; empty = off).\n"
                        + "When on, the mod installs a sniffing header stripper on the server network "
                        + "pipeline, so login logs and bans\n"
                        + "see the player's real source address instead of 127.0.0.1.\n"
                        + "Current frp only sends the header on the stcp relay path; xtcp P2P streams "
                        + "pick this up once upstream supports it.\n"
                        + "With runAgent=false, pass the same -proxy-protocol flag to the standalone "
                        + "serve yourself",
                "让隧道进程连本地 MC 端口前先发 PROXY protocol 头（填 v1 或 v2，留空关闭）。\n"
                        + "开启后本 mod 会给服务端接入链装嗅探式剥头组件，登录日志与封禁\n"
                        + "看到的是玩家真实来源地址而不是 127.0.0.1。\n"
                        + "当前 frp 只有 stcp 中转路径实际带头，xtcp 的 P2P 流等上游支持后自动生效。\n"
                        + "runAgent=false 时须给独立运行的 serve 手动加同值的 -proxy-protocol 旗标");
        def("cfg.server.preauth",
                "Let players obtain direct-connection credentials on the Minecraft port before joining "
                        + "(no extra listening port).\n"
                        + "With this on, players need no relay join on first launch or after a secret "
                        + "rotation.\n"
                        + "No identity check is performed; admission stays with the MC server's own "
                        + "whitelist and online-mode auth.\n"
                        + "Note: the exchange frames are unencrypted; credentials cross the network in "
                        + "plain text",
                "允许玩家在进服之前于 Minecraft 端口上换取直连凭证（不另开监听端口）。\n"
                        + "开启后玩家首次启动、密钥轮换之后都无需先经中转进服。\n"
                        + "不做身份验证——准入交给 MC 服务端自己的白名单与正版验证。\n"
                        + "注意：交换帧不加密，凭证以明文过网");
        def("cfg.client.category",
                "Client side only. Timing defaults come from field measurements; a smooth setup takes "
                        + "about 2-5 seconds.\n"
                        + "The default behavior is fully automatic direct connections: credentials are "
                        + "prefetched at startup and\n"
                        + "tunnels are punched in the background (prewarm, retrying with backoff for as "
                        + "long as it fails);\n"
                        + "players just click their usual entry in the server list.",
                "客户端专用：时间参数默认值来自实测，顺利时建链约 2-5 秒。\n"
                        + "默认行为是全自动直连：启动时预取凭证（prefetch）、后台打洞（prewarm，\n"
                        + "打不通按退避一直重试），玩家只管点服务器列表里的直连条目。");
        def("cfg.client.enabled",
                "Whether to react to server-delivered credentials and attempt a direct connection",
                "是否响应服务端下发的凭证并尝试直连");
        def("cfg.client.prewarm",
                "Warm up one direct tunnel per known service credential at game startup and expose a "
                        + "matching direct entry\n"
                        + "in the server list. Punching is strictly serial to avoid interference on the "
                        + "same NAT; established tunnels\n"
                        + "stay alive concurrently. Failing services keep retrying on their own backoff "
                        + "schedules",
                "游戏启动时为每份已知服务凭证预热一条直连隧道，并在服务器列表里\n"
                        + "提供对应直连入口。打洞严格串行以避免同 NAT 干扰，已建立隧道可同时存活；\n"
                        + "失败服务各自按退避周期持续重试");
        def("cfg.client.replaceServerEntries",
                "Once warm-up is ready, resolve the original server entry's connection target to the "
                        + "local tunnel at runtime.\n"
                        + "servers.dat is never modified; the next launch still prefetches and falls back "
                        + "through the real entry.\n"
                        + "When off, a separate direct entry (named by directEntryName) is maintained in "
                        + "servers.dat instead",
                "预热就绪后是否在运行期把原服务器条目的连接目标解析到本地隧道。\n"
                        + "不会修改 servers.dat；下次启动仍用真实入口预取和回退。\n"
                        + "关闭后改为在 servers.dat 中维护独立的 [P2P直连] 条目");
        def("cfg.client.redirectOnWarmReady",
                "While playing over the relay, switch to the background direct connection as soon as it "
                        + "becomes ready.\n"
                        + "The switch looks like one quick reconnect (a few seconds); each service "
                        + "auto-switches at most twice per session.\n"
                        + "When off the tunnel is still built, but you stay on the relay until you "
                        + "reconnect yourself",
                "经中转在服务器里游玩期间，后台直连一旦就绪是否立即切换过去。\n"
                        + "切换表现为一次快速重连（约数秒），每个服务本会话最多自动切换两次。\n"
                        + "关闭后隧道仍会建立，但要等你自己断开重连才走直连");
        def("cfg.client.prewarmPort",
                "Local port for warm tunnels; if taken, a free port is picked automatically (entry "
                        + "addresses follow);\n"
                        + "0 means a random port every time",
                "预热隧道的本地端口，被占用时自动改用空闲端口（条目地址会跟着更新）；\n"
                        + "0 表示每次随机");
        def("cfg.client.directEntryName",
                "Name prefix of direct entries in the server list; each service appends its room and "
                        + "source entry.\n"
                        + "After a change, entries under the old name are no longer maintained and must "
                        + "be removed by hand",
                "服务器列表中直连条目的名字前缀；每个服务会追加房间与来源入口。\n"
                        + "改动后旧名字的条目不再被维护，需手动删除");
        def("cfg.client.prefetch",
                "Prefetch credentials straight from the Minecraft server port at startup,\n"
                        + "so no relay join is needed on first launch or after a secret rotation.\n"
                        + "Silently inactive when the server has server.preauth off",
                "启动时直接向 Minecraft 服务器端口预取凭证，\n"
                        + "首次启动、密钥轮换后都无需先经中转进服。\n"
                        + "服务端没开 server.preauth 时本项静默不生效");
        def("cfg.client.prefetchServers",
                "Minecraft server addresses to ask when prefetching credentials, one host or host:port "
                        + "per line\n"
                        + "(port defaults to 25565). Leave empty to use the entries in the server list "
                        + "(server.dat).\n"
                        + "Every address that answers successfully is cached and gets its own direct entry",
                "预取凭证时要问的 Minecraft 服务器地址，每行一个 host 或 host:port\n"
                        + "（不填端口按 25565）。留空则用服务器列表（server.dat）里的条目。\n"
                        + "所有成功应答的地址都会被缓存并建立各自的直连条目");
        def("cfg.client.verboseLogging",
                "Write detailed direct-connection logs (agent events, parameter keys, diagnostics) to "
                        + "the game log at INFO;\n"
                        + "when off they drop to DEBUG (invisible with the default log configuration)",
                "把直连过程的详细日志（agent 事件、参数键、诊断输出）以 INFO 级别写进游戏日志；\n"
                        + "关闭后这些内容降为 DEBUG 级别（默认日志配置下不可见）");
        def("cfg.client.punchTimeoutSeconds",
                "Hole-punching timeout in seconds (a server-suggested value takes precedence when "
                        + "delivered)",
                "打洞超时秒数（服务端下发了建议值时以服务端为准）");
        def("cfg.client.probeIntervalMs",
                "Readiness probe interval in milliseconds",
                "就绪探测间隔毫秒数");
        def("cfg.client.probeTimeoutMs",
                "Timeout in milliseconds for a single readiness probe",
                "单次就绪探测超时毫秒数");
        def("cfg.client.startupGraceMs",
                "Extra milliseconds on top of the punch timeout for process startup and binary extraction",
                "打洞超时之外留给进程启动、二进制释放的余量毫秒数");
        def("cfg.client.warmupRetryInitialSeconds",
                "Seconds before the first retry after a failed warm-up punch (exponential backoff "
                        + "afterwards)",
                "预热打洞失败后的首次重试等待秒数（此后指数退避）");
        def("cfg.client.warmupRetryMaxSeconds",
                "Upper bound in seconds for warm-up retry backoff; keeps punching at this interval for "
                        + "as long as it fails",
                "预热重试退避的上限秒数——打不通就按这个周期一直打");
        def("cfg.client.prefetchTimeoutSeconds",
                "Prefetch timeout in seconds per candidate (including TCP round trips)",
                "单个候选的预取超时秒数（含 TCP 往返）");
        def("cfg.client.prefetchRefreshSeconds",
                "Seconds between background credential sweeps once tunnels are up; rotation is "
                        + "normally caught right away by agent health events, this sweep is only the "
                        + "fallback",
                "隧道就绪后的后台凭证对账间隔秒数——轮换通常由 agent 健康事件即时发现，这里只兜底");
        def("cfg.telemetry.category",
                "Anonymous quality measurement. Sends version, platform and coarse results; depending on "
                        + "the switches also\n"
                        + "punch stages, stable failure codes, retry counts, RTT/duration buckets and "
                        + "warmup/upgrade/prefetch paths.",
                "匿名质量测量。发送版本、平台与粗粒度结果；视开关发送\n"
                        + "打洞阶段、稳定失败码、重试次数、RTT/耗时桶、预热/升级/预取路径。");
        def("cfg.telemetry.enhanced",
                "Whether to send detailed anonymous quality metrics",
                "是否发送详细匿名质量指标");
    }

    private static void defTelemetry() {
        def("telemetry.status",
                "Netherway telemetry: {0}, pending summaries {1}, dropped {2}, {3}",
                "Netherway 遥测: {0}，待处理摘要 {1}，已丢弃 {2}，{3}");
        def("telemetry.transportConfigured",
                "transport configured",
                "传输已配置");
        def("telemetry.transportMissing",
                "no upload endpoint configured (local preview only)",
                "未配置上传端点（仅本地预览）");
        def("telemetry.previewLog",
                "Preview of the next telemetry payload: {0}",
                "下一批遥测 payload 预览: {0}");
        def("telemetry.previewWritten",
                "Telemetry preview written to {0} and printed to the game log",
                "遥测预览已写入 {0}，并输出到游戏日志");
        def("telemetry.previewFailed",
                "Failed to write the telemetry preview",
                "写入遥测预览失败");
        def("telemetry.previewFailedChat",
                "Failed to write the telemetry preview: {0}",
                "写入遥测预览失败: {0}");
    }
}
