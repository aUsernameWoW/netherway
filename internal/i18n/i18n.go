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
  netherway serve [options]    run on the server host; publishes the local Minecraft port for P2P
  netherway tunnel [options]   called by the Minecraft mod: pure P2P, gives up on timeout

Shared options:
  -backend <name>  tunnel backend, default gonc-p2p
  -O key=value     backend parameter, repeatable (gonc-p2p: sessionKey, room, brokers, stunServers, network)

serve only:
  -port        local port the Minecraft server listens on, default 25565
  -rendezvous  embedded signaling broker port (loopback). Non-zero starts the broker inside this
               process: the MC server forwards players' signaling connections in from the Minecraft
               port, and the "origin" entry of the brokers parameter resolves to it. The public
               side then only needs a plain TCP path to the Minecraft port
  -proxy-protocol  send a PROXY protocol header (v1/v2) before dialing the local MC port; the MC side must strip it

tunnel only:
  -port      local port to bind, default automatic
  -timeout   connection timeout in seconds, default 15; returns non-zero on expiry
  -log-file  backend log path (stdout is reserved for line-based JSON status)
  -v         verbose backend logging where the backend supports it
`,
		`netherway — Minecraft P2P 直连

用法:
  netherway serve [选项]    在服务器宿主机运行，把本机 Minecraft 端口发布为 P2P 入口
  netherway tunnel [选项]   供 Minecraft mod 调用：纯 P2P，超时即放弃

公共选项:
  -backend <名字>  隧道 backend，默认 gonc-p2p
  -O key=value     传给 backend 的参数，可重复（gonc-p2p：sessionKey、room、brokers、stunServers、network）

serve 专有:
  -port        Minecraft 服务器监听的本地端口，默认 25565
  -rendezvous  内嵌信令 broker 端口（回环）。非零即在本进程内起 broker：玩家的信令连接由
               MC 服务端从 Minecraft 端口转发进来，brokers 参数里的 "origin" 解析为它。
               公网侧因此只需要一条能到 Minecraft 端口的普通 TCP 通路
  -proxy-protocol  连本地 MC 端口前先发 PROXY protocol 头（v1/v2），MC 侧需能剥头

tunnel 专有:
  -port      本地监听端口，默认自动分配
  -timeout   建链超时秒数，默认 15，超时返回非零码
  -log-file  backend 日志路径（stdout 留给逐行 JSON 状态）
  -v         backend 支持时输出详细日志
`},

	// ---- shared flags ----
	"flag.verbose": {
		"verbose backend logging where the backend supports it",
		"backend 支持时输出详细日志"},

	// ---- serve ----
	"flag.serve.port": {
		"local port the Minecraft server listens on",
		"Minecraft 服务器监听的本地端口"},
	"flag.serve.proxyProtocol": {
		"send a PROXY protocol header (v1 or v2) before dialing the local MC port; the MC side must strip it; empty disables",
		"连本地 MC 端口前先发 PROXY protocol 头（v1 或 v2），MC 侧需能剥头；留空关闭"},
	"flag.serve.rendezvous": {
		"embedded signaling broker port (loopback); when non-zero the broker runs inside this process instead of using public brokers, the \"origin\" entry of the brokers parameter resolves to it, and the MC server forwards players' signaling connections in from the Minecraft port",
		"内嵌信令 broker 端口（回环）；非零时不用公网 broker，改在本进程内起 broker，brokers 参数里的 \"origin\" 解析为它，玩家的信令连接由 MC 服务端从 Minecraft 端口转发进来"},
	"flag.serve.backend": {
		"tunnel backend to publish with (gonc-p2p)",
		"发布用的隧道 backend（gonc-p2p）"},
	"flag.serve.param": {
		"backend parameter as key=value, repeatable",
		"backend 参数 key=value，可重复"},
	"serve.goncPublish": {
		"publishing local Minecraft port %d over gonc-p2p (MQTT signaling, no rendezvous server)",
		"经 gonc-p2p 发布本机 Minecraft 端口 %d（MQTT 信令，无会合点服务器）"},
	"serve.goncEmbeddedBroker": {
		"publishing local Minecraft port %d over gonc-p2p (embedded signaling broker at 127.0.0.1:%d, loopback only; players' MQTT connections are forwarded in from the Minecraft port)",
		"经 gonc-p2p 发布本机 Minecraft 端口 %d（内嵌信令 broker 127.0.0.1:%d，仅回环；玩家的 MQTT 连接经 Minecraft 端口转发进来）"},
	"serve.goncExternalBrokers": {
		"-rendezvous %d given, but parameter %s names explicit brokers without the %q placeholder: using those brokers, the embedded signaling broker is not started (list %q among them to use it)",
		"给了 -rendezvous %d，但参数 %s 写了显式 broker 且没有 %q 占位符：按这些 broker 运行，不启动内嵌信令 broker（想用它就把 %q 写进列表）"},
	"serve.goncOriginNeedsRendezvous": {
		"parameter %s contains the %q placeholder but -rendezvous is not set: it stands for the embedded signaling broker, which only exists under -rendezvous <port>",
		"参数 %s 含占位符 %q 但未设置 -rendezvous：它代表内嵌信令 broker，只在 -rendezvous <端口> 下存在"},
	"serve.goncProxyProtocolOn": {
		"PROXY protocol %s enabled: each player session's punched peer address is passed to the MC server in the header; the MC side must strip it, or players cannot connect",
		"PROXY protocol %s 已启用：每个玩家会话的打洞对端地址将随头透传给 MC 服务端；确保 MC 侧装有剥头组件，否则玩家会连不上"},
	"serve.goncProxyHeaderSkip": {
		"cannot build a PROXY header from peer address %q: %v; serving this session without one (the MC side will see 127.0.0.1)",
		"无法由对端地址 %q 组装 PROXY 头: %v；本会话不带头继续（MC 侧将看到 127.0.0.1）"},
	"serve.goncRetry": {
		"wait/punch cycle failed: %v; re-arming",
		"等待/打洞一轮失败: %v；重新武装"},
	"serve.goncWaitIdle": {
		"no player hello in this wait cycle; re-arming",
		"本轮等待没有玩家 hello；重新武装"},
	"serve.goncBrokerUnreachable": {
		"no signaling broker reachable: %v; retrying",
		"没有可达的信令 broker: %v；重试中"},
	"serve.goncReady": {
		"gonc-p2p serve ready: a signaling broker is reachable, local Minecraft port %d is published",
		"gonc-p2p serve 就绪：信令 broker 可达，本机 Minecraft 端口 %d 已发布"},
	"serve.goncSession": {
		"player session established (peer %s via %s)",
		"玩家会话已建立（对端 %s，经 %s）"},
	"serve.goncSessionEnd": {
		"player session ended (peer %s)",
		"玩家会话结束（对端 %s）"},
	"serve.badProxyProtocol": {
		"-proxy-protocol only accepts v1 or v2 (got %q)",
		"-proxy-protocol 只接受 v1 或 v2（收到 %q）"},

	// ---- tunnel ----
	"flag.tunnel.backend": {
		"tunnel backend (gonc-p2p)",
		"隧道 backend（gonc-p2p）"},
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
		"punch timeout %.1fs, probe interval %.2fs with %.1fs per-probe timeout; backend log: %s (level %s)",
		"打洞超时 %.1fs，就绪探测间隔 %.2fs、单次超时 %.1fs；backend 日志: %s（级别 %s）"},
	"tunnel.exitedEarly": {
		"the tunnel exited early",
		"隧道提前退出"},
	"tunnel.probeFailed": {
		"connection timed out: %v",
		"建链超时: %v"},
	"tunnel.notReadyIn": {
		"the tunnel did not become ready within %.1fs",
		"隧道未在 %.1fs 内就绪"},

	// ---- NAT probe (telemetry) ----
	"nat.probeFailed": {
		"NAT probe inconclusive: %d STUN address(es) via %d server(s), err=%v",
		"NAT 探测未果：%d 个 STUN 地址（%d 台服务器），err=%v"},
	"nat.classifyFailed": {
		"NAT type %q (on %s) has no telemetry mapping; omitting",
		"NAT 类型 %q（%s）没有遥测映射，省略"},
	"nat.classified": {
		"NAT classified as %s (gonc type %s on %s, %d STUN candidate(s), took %dms)",
		"NAT 分类: %s（gonc 类型 %s，%s，%d 个 STUN 候选，耗时 %dms）"},

	// ---- embedded signaling broker (the gonc-p2p rendezvous) ----
	"sb.badPort": {
		"invalid signaling broker port: %d",
		"信令 broker 端口非法: %d"},
	"sb.listen": {
		"listen for the embedded signaling broker on 127.0.0.1:%d: %w",
		"内嵌信令 broker 监听 127.0.0.1:%d: %w"},
	"sb.start": {
		"start embedded signaling broker: %w",
		"启动内嵌信令 broker: %w"},
	"sb.stopped": {
		"embedded signaling broker stopped",
		"内嵌信令 broker 已停止"},
	"sb.log": {
		"signaling broker: %s",
		"信令 broker: %s"},

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

	// ---- gonc-p2p backend ----
	"goncp2p.unknownKeys": {
		"ignoring unknown parameter keys %v (keys gonc-p2p understands: %s)",
		"忽略未知参数键 %v（gonc-p2p 认识的键: %s）"},
	"goncp2p.noSessionKey": {
		"session key is empty: pass parameter %s (the server generates it and hands it out in credentials)",
		"会话密钥为空：请传参数 %s（由服务端生成并随凭证下发）"},
	"goncp2p.badNetwork": {
		"invalid parameter %s: %q (allowed: %s)",
		"参数 %s 非法: %q（允许: %s）"},
	"goncp2p.originUnresolved": {
		"parameter %s still contains the %q placeholder: the client mod replaces it with the Minecraft entry the credential came from, and serve -rendezvous with its embedded broker; it must not reach the backend unresolved",
		"参数 %s 仍含占位符 %q：客户端 mod 应把它换成凭证来源的 Minecraft 入口，serve -rendezvous 应换成内嵌 broker；未解析的占位符不能进 backend"},
	"goncp2p.effective": {
		"effective parameters: %s=%s %s=%s %s=%s %s=%s",
		"生效参数: %s=%s %s=%s %s=%s %s=%s"},
	"goncp2p.empty": {
		"unset",
		"空"},
	"goncp2p.set": {
		"set (%d bytes)",
		"已设置(%d字节)"},
	"goncp2p.defaultList": {
		"(built-in defaults)",
		"（内置默认列表）"},
	"goncp2p.retry": {
		"P2P attempt failed: %v; retrying in %v",
		"打洞尝试失败: %v；%v 后重试"},
	"goncp2p.established": {
		"P2P session established with %s via %s; serving local port %d",
		"已与 %s 经 %s 建立 P2P 会话；本地端口 %d 开始服务"},
	"goncp2p.sessionLost": {
		"tunnel session lost (peer or transport gone)",
		"隧道会话已断开（对端或传输层消失）"},
	"goncp2p.logOpenFailed": {
		"cannot open log file %s: %v; backend diagnostics go to the echo stream only",
		"无法打开日志文件 %s: %v；backend 诊断只走回显通道"},
}
