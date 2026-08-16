// Package i18n 是 agent 控制台输出的极简消息目录。
//
// 与 Java 侧 core 的 L10n 同一套设计：目录写在代码里、en/zh 并排，
// 改一条文案不会漏掉另一种语言；一致性由本包测试钉住（en/zh 的
// fmt 动词序列必须一致、en 不含中文）。
//
// 语言判定只在首次取用时做一次：NETHERWAY_LANG 优先（mod 启动
// 子进程时按自己的语言写入，见 Java 侧 AgentProcess.applyLanguage），
// 其次按 POSIX 惯例看 LC_ALL/LC_MESSAGES/LANG，全都没有则英文。
// stdout 的 JSON 契约（event/failureCode 等）不经这里，永远是稳定枚举。
package i18n

import (
	"fmt"
	"os"
	"strings"
	"sync"
)

// Lang 是目录的语言下标。
type Lang int

const (
	EN Lang = iota
	ZH
)

var (
	once sync.Once
	lang Lang
)

// Language 返回本进程的输出语言（首次调用时判定，之后不变）。
func Language() Lang {
	once.Do(func() { lang = detect(os.Getenv) })
	return lang
}

// Use 强制指定语言并跳过环境判定。正常运行不需要（进程语言由环境变量
// 决定）；测试用它钉住文案断言的语言。
func Use(l Lang) {
	once.Do(func() {})
	lang = l
}

func detect(getenv func(string) string) Lang {
	// LC_ALL 优先于 LC_MESSAGES 优先于 LANG 是 POSIX 语义；
	// NETHERWAY_LANG 是本项目自己的开关，放最前。
	for _, k := range []string{"NETHERWAY_LANG", "LC_ALL", "LC_MESSAGES", "LANG"} {
		v := strings.ToLower(strings.TrimSpace(getenv(k)))
		if v == "" {
			continue
		}
		if strings.HasPrefix(v, "zh") {
			return ZH
		}
		return EN
	}
	return EN
}

// T 取 key 的当前语言文案；有参数时按 fmt.Sprintf 填充。
// 缺 key 时返回 key 与参数本身，绝不 panic——文案问题不能影响流程。
func T(key string, args ...any) string {
	e, ok := catalog[key]
	if !ok {
		if len(args) == 0 {
			return key
		}
		return fmt.Sprintf("%s %v", key, args)
	}
	f := e[Language()]
	if len(args) == 0 {
		return f
	}
	return fmt.Sprintf(f, args...)
}

// Errorf 是 fmt.Errorf 的目录版；目录文案里的 %w 照常生效。
func Errorf(key string, args ...any) error {
	e, ok := catalog[key]
	if !ok {
		return fmt.Errorf("%s %v", key, args)
	}
	return fmt.Errorf(e[Language()], args...)
}

// 目录。下标 0 是 en，1 是 zh，与 Lang 常量一致。
var catalog = map[string][2]string{
	// ---- main / usage ----
	"main.unknownCommand": {
		"unknown subcommand: %s",
		"未知子命令: %s"},
	"main.error": {
		"error: %v",
		"错误: %v"},
	"main.noFreePort": {
		"no free port available: %w",
		"找不到可用端口: %w"},
	"main.usage": {
		`netherway — Minecraft P2P direct connect

Usage:
  netherway serve [options]    run on the server host; publishes a local port as a P2P proxy
  netherway tunnel [options]   called by the Minecraft mod: pure P2P, gives up on timeout
  netherway authplugin [options]  run on the frps host: per-player token verification (frps httpPlugins)

Common options:
  -server  frps address     -port    port
  -token   frps token       -stun    STUN server
  -room    room name        -secret  room secret
  -v       debug logging

serve only:
  -meta-token  static token identifying this serve to the authplugin (same value as authplugin -static-token)
  -proxy-protocol  send a PROXY protocol header (v1/v2) before dialing the local MC port; the MC side must strip it
  -rendezvous  embedded rendezvous port (loopback). Non-zero enables it: no public frps is dialed;
               the rendezvous runs locally and the MC server forwards players' control connections
               in from the Minecraft port. The public side then only needs a dumb TCP tunnel
               to the Minecraft port
  -signing-key per-player token signing key (embedded-rendezvous mode only)

tunnel only:
  -backend   tunnel backend, default frp-xtcp
  -O key=value  backend parameter, repeatable; for frp-xtcp the common options above also work
  -timeout   connection timeout in seconds, default 15; returns non-zero on expiry
  -log-file  backend log path (stdout is reserved for line-based JSON status)

authplugin only:
  -listen    listen address, default 127.0.0.1:7200    -path  HTTP path, default /handler
  -key       token signing key (or the NETHERWAY_AUTH_KEY environment variable)
  -static-token  static token allowlist, repeatable    -allow-legacy  allow tokenless logins during migration

Options left unset fall back to build-time injected defaults.
`,
		`netherway — Minecraft P2P 直连

用法:
  netherway serve [选项]    在服务器宿主机运行，把本地端口发布为 P2P 代理
  netherway tunnel [选项]   供 Minecraft mod 调用：纯 P2P，超时即放弃
  netherway authplugin [选项]  在 frps 宿主机运行：每玩家令牌校验（frps httpPlugins）

公共选项:
  -server  frps 地址        -port    端口
  -token   frps 令牌        -stun    STUN 服务器
  -room    房间名           -secret  房间密钥
  -v       输出调试日志

serve 专有:
  -meta-token  向 authplugin 表明身份的静态令牌（authplugin -static-token 同值）
  -proxy-protocol  连本地 MC 端口前先发 PROXY protocol 头（v1/v2），MC 侧需能剥头
  -rendezvous  内嵌会合点端口（回环）。非零即启用：不连公网 frps，改在本机起
               会合点，玩家的控制连接由 MC 服务端从 Minecraft 端口转发进来。
               公网侧因此只需要一条能到 Minecraft 端口的哑 TCP 隧道
  -signing-key 每玩家令牌签发密钥（仅内嵌会合点模式）

tunnel 专有:
  -backend   隧道方案，默认 frp-xtcp
  -O key=value  传给 backend 的参数，可重复；frp-xtcp 也可直接用上面的公共选项
  -timeout   建链超时秒数，默认 15，超时返回非零码
  -log-file  backend 日志路径（stdout 留给逐行 JSON 状态）

authplugin 专有:
  -listen    监听地址，默认 127.0.0.1:7200    -path  HTTP 路径，默认 /handler
  -key       令牌签发密钥（或环境变量 NETHERWAY_AUTH_KEY）
  -static-token  静态令牌白名单，可重复      -allow-legacy  迁移期放行无令牌登录

未指定的选项使用构建时注入的默认值。
`},

	// ---- 公共旗标 ----
	"flag.server": {
		"frps address",
		"frps 地址"},
	"flag.serverPort": {
		"frps port",
		"frps 端口"},
	"flag.token": {
		"frps token",
		"frps 令牌"},
	"flag.stun": {
		"STUN server",
		"STUN 服务器"},
	"flag.room": {
		"room name",
		"房间名"},
	"flag.secret": {
		"room secret",
		"房间密钥"},
	"flag.verbose": {
		"enable debug logging",
		"输出调试日志"},

	// ---- serve ----
	"flag.serve.port": {
		"local port the Minecraft server listens on",
		"Minecraft 服务器监听的本地端口"},
	"flag.serve.metaToken": {
		"static token identifying this serve to the frps authplugin; not needed when frps has no authplugin",
		"向 frps 的 authplugin 表明身份的静态令牌；frps 未部署 authplugin 时不需要"},
	"flag.serve.proxyProtocol": {
		"send a PROXY protocol header (v1 or v2) before dialing the local MC port; the MC side must strip it; empty disables",
		"连本地 MC 端口前先发 PROXY protocol 头（v1 或 v2），MC 侧需能剥头；留空关闭"},
	"flag.serve.rendezvous": {
		"embedded rendezvous port (loopback); when non-zero, do not dial a public frps but run the rendezvous locally — the MC server forwards players' control connections in from the Minecraft port",
		"内嵌会合点端口（回环）；非零时不连公网 frps，改在本机起会合点，玩家的控制连接由 MC 服务端从 Minecraft 端口转发进来"},
	"flag.serve.signingKey": {
		"per-player token signing key; only meaningful in embedded-rendezvous mode (same value as the server mod's tokenSigningKey)",
		"每玩家令牌签发密钥，仅内嵌会合点模式下有意义（与服务端 mod 的 tokenSigningKey 同值）"},
	"serve.publish": {
		"publishing local port %d as room %q (P2P)",
		"发布本地端口 %d 为房间 %q（P2P）"},
	"serve.publishEmbedded": {
		"publishing local port %d as room %q (P2P, embedded rendezvous at 127.0.0.1:%d)",
		"发布本地端口 %d 为房间 %q（P2P，内嵌会合点 127.0.0.1:%d）"},
	"serve.proxyProtocolOn": {
		"PROXY protocol %s enabled: make sure the MC server strips the header, or players cannot connect (with the current frp version only the stcp relay path actually carries it)",
		"PROXY protocol %s 已启用：确保 MC 服务端装有剥头组件，否则玩家会连不上（当前 frp 版本仅 stcp 中转路径实际带头）"},
	"serve.badProxyProtocol": {
		"-proxy-protocol only accepts v1 or v2 (got %q)",
		"-proxy-protocol 只接受 v1 或 v2（收到 %q）"},
	"serve.staticTokenIgnored": {
		"static token ignored: without a signing key (tokenSigningKey in the server cfg) there are no per-player tokens to verify, so the embedded rendezvous does not enable its auth endpoint",
		"已忽略静态令牌：未配置签发密钥（服务端 cfg 的 tokenSigningKey）时没有每玩家令牌可校验，内嵌会合点不启用鉴权端点"},
	"serve.selfTokenGenerated": {
		"serve's own static token was randomly generated for this run (used only to identify itself to the embedded auth endpoint)",
		"serve 自用静态令牌本次启动随机生成（仅用于向内嵌鉴权端点自证身份）"},

	// ---- tunnel ----
	"flag.tunnel.backend": {
		"tunnel backend",
		"隧道 backend"},
	"flag.tunnel.param": {
		"backend parameter, repeatable: -O key=value",
		"backend 参数，可重复：-O key=value"},
	"flag.tunnel.port": {
		"local port to bind, 0 for automatic",
		"本地监听端口，0 表示自动分配"},
	"flag.tunnel.timeout": {
		"connection timeout in seconds; give up on expiry",
		"建链超时秒数，超时则放弃升级"},
	"flag.tunnel.probeInterval": {
		"readiness probe interval in seconds",
		"就绪探测间隔秒数"},
	"flag.tunnel.probeTimeout": {
		"single readiness probe timeout in seconds",
		"单次就绪探测超时秒数"},
	"flag.tunnel.retryInterval": {
		"minimum retry interval in seconds after a failed attempt",
		"建链失败后最小重试间隔秒数"},
	"flag.tunnel.maxRetries": {
		"maximum connection retries per hour",
		"每小时建链重试次数上限"},
	"flag.tunnel.logFile": {
		"backend log file, defaults to the system temp directory",
		"backend 日志文件，默认写入系统临时目录"},
	"tunnel.paramCount": {
		"%d params",
		"%d 个参数"},
	"tunnel.badParamFormat": {
		"parameter must be key=value: %q",
		"参数格式应为 key=value: %q"},
	"tunnel.unknownBackend": {
		"unknown backend %q, available: %s",
		"未知 backend %q，可用: %s"},
	"tunnel.diagParams": {
		"backend %s, local port %d, received parameter keys: %s",
		"backend %s，本地端口 %d，收到参数键: %s"},
	"tunnel.diagTimings": {
		"punch timeout %.1fs, probe interval %.2fs with %.1fs per-probe timeout; frp log: %s (level %s)",
		"打洞超时 %.1fs，就绪探测间隔 %.2fs、单次超时 %.1fs；frp 日志: %s（级别 %s）"},
	"tunnel.exitedEarly": {
		"the tunnel exited early",
		"隧道提前退出"},
	"tunnel.probeFailed": {
		"connection timed out: %v",
		"建链超时: %v"},
	"tunnel.notReadyIn": {
		"the tunnel did not become ready within %.1fs",
		"隧道未在 %.1fs 内就绪"},

	// ---- authplugin（子命令与校验逻辑）----
	"flag.auth.listen": {
		"listen address; should be reachable by frps only, usually 127.0.0.1",
		"监听地址；应只对 frps 可达，通常是 127.0.0.1"},
	"flag.auth.path": {
		"HTTP path, must match httpPlugins.path in frps.toml",
		"HTTP 路径，与 frps.toml 的 httpPlugins.path 一致"},
	"flag.auth.key": {
		"per-player token signing key, must match the server mod's server.tokenSigningKey;\ncan also be passed via the NETHERWAY_AUTH_KEY environment variable (keeps it out of the process list)",
		"每玩家令牌的签发密钥，须与服务端 mod 的 server.tokenSigningKey 一致；\n也可经环境变量 NETHERWAY_AUTH_KEY 传入（避免出现在进程列表里）"},
	"flag.auth.allowLegacy": {
		"allow logins and proxy registrations without a token (for migration; disable once stable)",
		"放行不带令牌的登录与代理注册（迁移期用，稳定后应关闭）"},
	"flag.auth.staticToken": {
		"static token, repeatable; the serve side carries the same value via -meta-token to identify itself and register proxies",
		"静态令牌，可重复；serve 端用 -meta-token 携带同一值以表明身份并获准注册代理"},
	"auth.count": {
		"%d item(s)",
		"%d 个"},
	"auth.noKey": {
		"no signing key: pass -key or set the NETHERWAY_AUTH_KEY environment variable",
		"未指定签发密钥：用 -key 或环境变量 NETHERWAY_AUTH_KEY"},
	"auth.listening": {
		"authplugin listening on %s%s, signing key fingerprint %s, %d static token(s), legacy=%v",
		"authplugin 监听 %s%s，签发密钥指纹 %s，静态令牌 %d 个，legacy=%v"},
	"auth.stopped": {
		"authplugin stopped",
		"authplugin 已停止"},
	"auth.badToken": {
		"malformed token",
		"令牌格式错误"},
	"auth.missingUser": {
		"missing metas.user",
		"缺少 metas.user"},
	"auth.expired": {
		"token expired at %s (join once via the relay to renew it automatically)",
		"令牌已于 %s 过期（重新经中转进服一次即自动续签）"},
	"auth.verifyFailed": {
		"token verification failed",
		"令牌校验失败"},
	"auth.legacyLogin": {
		"allowing legacy login (no token): %s",
		"放行 legacy 登录（未带令牌）: %s"},
	"auth.rejectNoToken": {
		"rejecting login (no token, and -allow-legacy is off): %s",
		"拒绝登录（未带令牌，且未开 -allow-legacy）: %s"},
	"auth.rejectNoTokenReason": {
		"login carried no token; old clients should join once via the server relay to obtain fresh credentials",
		"登录未携带令牌；旧客户端请重新经服务器中转进服一次以获取新凭证"},
	"auth.staticLogin": {
		"allowing static-token login: %s",
		"放行静态令牌登录: %s"},
	"auth.rejectLogin": {
		"rejecting login (user=%s, %v): %s",
		"拒绝登录（user=%s, %v）: %s"},
	"auth.allowLogin": {
		"allowing player login: user=%s %s",
		"放行玩家登录: user=%s %s"},
	"auth.legacyProxy": {
		"allowing proxy registration from a legacy session: %s (%s)",
		"放行 legacy 会话注册代理: %s (%s)"},
	"auth.rejectProxyNoStatic": {
		"rejecting proxy registration (no static token): %s (%s)",
		"拒绝注册代理（未带静态令牌）: %s (%s)"},
	"auth.rejectProxyNoStaticReason": {
		"sessions without a static token may not register proxies",
		"未带静态令牌的会话不允许注册代理"},
	"auth.allowProxy": {
		"allowing proxy registration: %s (%s)",
		"放行代理注册: %s (%s)"},
	"auth.rejectProxyPlayer": {
		"rejecting proxy registration (player token, user=%s): %s (%s)",
		"拒绝注册代理（玩家令牌，user=%s）: %s (%s)"},
	"auth.rejectProxyPlayerReason": {
		"player tokens may not register proxies",
		"玩家令牌不允许注册代理"},

	// ---- NAT 探测 ----
	"nat.probeFailed": {
		"NAT probe via %s inconclusive (err=%v, %d mapped addresses)",
		"NAT 探测经 %s 未果（err=%v，%d 个映射地址）"},
	"nat.classifyFailed": {
		"NAT classification failed (%s): %v",
		"NAT 分类失败（%s）: %v"},
	"nat.classified": {
		"NAT classified as %s (behavior=%s public=%v, via %s, took %dms)",
		"NAT 分类: %s（behavior=%s public=%v，经 %s，耗时 %dms）"},

	// ---- 会合点 ----
	"rz.badPort": {
		"invalid rendezvous port: %d",
		"会合点端口非法: %d"},
	"rz.badAddr": {
		"invalid rendezvous address: %q",
		"会合点地址非法: %q"},
	"rz.mustLoopback": {
		"the rendezvous may only bind a loopback address (got %s): its only entrance is the byte relay on the Minecraft port",
		"会合点只能绑回环地址（收到 %s）：它的唯一入口是 Minecraft 端口上的字节转发"},
	"rz.tokenGen": {
		"generate rendezvous token: %w",
		"生成会合点令牌: %w"},
	"rz.tokenGenerated": {
		"the rendezvous token was randomly generated for this run (rotates on restart)",
		"会合点令牌本次启动随机生成（重启即轮换）"},
	"rz.completeConfig": {
		"complete rendezvous configuration: %w",
		"补全会合点配置: %w"},
	"rz.create": {
		"create rendezvous: %w",
		"创建会合点: %w"},
	"rz.listening": {
		"rendezvous listening on %s:%d (loopback only; players are forwarded in from the Minecraft port)",
		"会合点已在 %s:%d 监听（仅回环；玩家经 Minecraft 端口转发进来）"},
	"rz.authStart": {
		"start embedded auth endpoint: %w",
		"启动内嵌鉴权端点: %w"},
	"rz.authEnabled": {
		"embedded per-player token verification enabled (signing key fingerprint %s)",
		"内嵌每玩家令牌校验已启用（签发密钥指纹 %s）"},
	"rz.stopped": {
		"rendezvous stopped",
		"会合点已停止"},

	// ---- mcping ----
	"mcping.varintTooLong": {
		"varint too long",
		"varint 过长"},
	"mcping.badStatusLen": {
		"unexpected status response length: %d",
		"状态响应长度异常: %d"},
	"mcping.parseStatus": {
		"parse status JSON: %w",
		"解析状态 JSON: %w"},
	"mcping.timeout": {
		"timed out",
		"超时"},

	// ---- config ----
	"config.emptyRoom": {
		"room name is empty: pass -room, or inject DefaultRoom at build time",
		"房间名为空：用 -room 指定，或在构建时注入 DefaultRoom"},
	"config.emptySecret": {
		"room secret is empty: pass -secret, or inject DefaultSecretKey at build time",
		"密钥为空：用 -secret 指定，或在构建时注入 DefaultSecretKey"},

	// ---- frp-xtcp backend ----
	"frpxtcp.unknownKeys": {
		"ignoring unknown parameter keys %v (keys frp-xtcp understands: %s)",
		"忽略未知参数键 %v（frp-xtcp 认识的键: %s）"},
	"frpxtcp.badPort": {
		"invalid parameter %s: %q",
		"参数 %s 非法: %q"},
	"frpxtcp.effective": {
		"effective parameters: %s=%s %s=%d %s=%s %s=%s %s=%s %s=%s %s=%s %s=%s",
		"生效参数: %s=%s %s=%d %s=%s %s=%s %s=%s %s=%s %s=%s %s=%s"},
	"frpxtcp.empty": {
		"unset",
		"空"},
	"frpxtcp.set": {
		"set (%d bytes)",
		"已设置(%d字节)"},
	"frpxtcp.noParams": {
		"%w (no parameters were received this time; all build-time defaults are in use)",
		"%w（本次未收到任何参数，全部使用构建期默认值）"},
	"frpxtcp.withKeys": {
		"%w (received parameter keys: %s)",
		"%w（收到的参数键: %s）"},

	// ---- STUN ----
	"stun.picked": {
		"STUN: picked %[2]s out of %[1]d candidate(s)",
		"STUN: 从 %[1]d 个候选中选用 %[2]s"},
	"stun.tooFew": {
		"returned only %d address(es), frp needs 2",
		"只返回 %d 个地址，frp 需要 2 个"},
	"stun.timeout": {
		"STUN probing timed out; known failures: %s",
		"STUN 探测超时；已知失败: %s"},
	"stun.noneUsable": {
		"no usable STUN server: %s",
		"没有可用的 STUN 服务器: %s"},

	// ---- tunnel（frp 装配）----
	"tunnel.noServer": {
		"no frps address: pass -server, or inject one at build time",
		"未指定 frps 地址：用 -server 指定，或在构建时注入"},
	"tunnel.noToken": {
		"no frps token: pass -token, or inject one at build time",
		"未指定 frps 令牌：用 -token 指定，或在构建时注入"},
	"tunnel.completeClientConfig": {
		"complete client configuration: %w",
		"补全客户端配置: %w"},
	"tunnel.validateConfig": {
		"configuration validation: %w",
		"配置校验: %w"},
	"tunnel.configWarning": {
		"configuration warning: %v",
		"配置警告: %v"},
	"tunnel.loadProxyConfig": {
		"load proxy configuration: %w",
		"装载代理配置: %w"},
	"tunnel.createService": {
		"create frpc service: %w",
		"创建 frpc 服务: %w"},
}
