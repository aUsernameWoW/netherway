package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"maps"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/aUsernameWoW/netherway/internal/backend"
	"github.com/aUsernameWoW/netherway/internal/backend/frpxtcp"
	"github.com/aUsernameWoW/netherway/internal/config"
	"github.com/aUsernameWoW/netherway/internal/i18n"
	"github.com/aUsernameWoW/netherway/internal/mcping"
)

// tunnel 子命令供 Minecraft mod 作为子进程调用。
//
// 与 join 的区别：
//   - 不做局域网广播，mod 直接把连接指向本地端口
//   - 不带兜底通道：玩家此刻已经通过既有中转隧道连着服务器，
//     建链失败就该留在那条连接上，而不是再开一条中转
//   - stdout 输出逐行 JSON 状态，供 mod 解析；backend 自身日志改写到文件
//   - 建链超时未就绪则退出并返回非零码，mod 据此放弃升级
//
// 具体隧道方案经 internal/backend 抽象：这里只负责选端口、起 backend、
// 用 Minecraft 握手探测就绪、输出状态 JSON，四件事都与方案无关。
// 注册新 backend 见 backends.go。

type event struct {
	Event string `json:"event"`
	// Backend 仅 starting 携带，标明本次用的隧道方案，便于日志排查。
	Backend string `json:"backend,omitempty"`
	Port    int    `json:"port,omitempty"`
	// ElapsedMs 是从进程启动到隧道可用的总耗时，含建链前置工作，
	// mod 用它决定要不要在界面上提示玩家「正在建立直连」。
	ElapsedMs int64 `json:"elapsedMs,omitempty"`
	// RTTMs 是隧道就绪后单独测得的往返延迟，不含建链开销，
	// 可直接拿来和中转线路比较。
	RTTMs   int64  `json:"rttMs,omitempty"`
	Version string `json:"version,omitempty"`
	Online  int    `json:"online,omitempty"`
	// FailureStage/FailureCode 是供统计使用的稳定、低基数字段；Reason 只供
	// 本地诊断日志展示，不能作为遥测维度或上传内容。
	FailureStage string `json:"failureStage,omitempty"`
	FailureCode  string `json:"failureCode,omitempty"`
	// Nat 是 STUN 探得的本机 NAT 形态（easy/hard），随终态事件携带；
	// 未探得则省略。见 natprobe.go。
	Nat    string `json:"nat,omitempty"`
	Reason string `json:"reason,omitempty"`
}

const (
	failureStageStart   = "start"
	failureStageBackend = "backend"
	failureStageProbe   = "probe"

	failureCodeBackendUnknown    = "backend_unknown"
	failureCodeBindPortFailed    = "bind_port_failed"
	failureCodeBackendExited     = "backend_exited"
	failureCodeReadyProbeTimeout = "ready_probe_timeout"
)

func failedEvent(stage, code, reason string) event {
	return event{
		Event:        "failed",
		FailureStage: stage,
		FailureCode:  code,
		Reason:       reason,
	}
}

// measureRTT 连测几次取最小值。隧道刚建立时首条连接会带上协商开销，
// 单次测量会严重高估延迟，最小值更接近稳定后的真实往返。
// 全部失败返回 0，由 mod 按「未知」处理。
//
// 采样必须挤在 deadline 之前：mod 只等 punchTimeout+startupGrace，慢路径
// 打洞在临期才就绪时，再花 3×probeTimeout 采样会把 ready 事件推出等待
// 窗口——一条刚建成的隧道反而被 mod 判成超时。宁可少测或不测（rtt=0
// 即「未知」），也不能耽误 ready 的发出。
func measureRTT(port int, timeout time.Duration, deadline time.Time) int64 {
	const samples = 3
	best := int64(0)
	for range samples {
		remain := time.Until(deadline)
		if remain <= 0 {
			break
		}
		if remain < timeout {
			timeout = remain
		}
		_, rtt, err := mcping.Ping("127.0.0.1", port, timeout)
		if err != nil {
			continue
		}
		ms := rtt.Milliseconds()
		if best == 0 || ms < best {
			best = ms
		}
	}
	return best
}

func emit(e event) {
	b, err := json.Marshal(e)
	if err != nil {
		return
	}
	fmt.Fprintln(os.Stdout, string(b))
}

// paramFlags 收集可重复的 -O key=value。
type paramFlags map[string]string

func (p paramFlags) String() string {
	return i18n.T("tunnel.paramCount", len(p))
}

func (p paramFlags) Set(s string) error {
	eq := strings.IndexByte(s, '=')
	if eq <= 0 {
		return i18n.Errorf("tunnel.badParamFormat", s)
	}
	p[s[:eq]] = s[eq+1:]
	return nil
}

func sortedKeys(m map[string]string) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// mergedParams 组装传给 backend 的参数表。
//
// frp 的老旗标（-server/-token/…）作为糖保留：手工调试和旧调用方都还能用。
// 只并入显式传了的，未传的留给 backend 用构建期注入的默认值补齐；
// -O 是新契约，写了就覆盖同名的糖。
func mergedParams(fs *flag.FlagSet, backendName string, explicit map[string]string) map[string]string {
	merged := map[string]string{}
	if backendName == frpxtcp.Name {
		sugar := map[string]string{
			"server":      frpxtcp.ParamServer,
			"server-port": frpxtcp.ParamServerPort,
			"token":       frpxtcp.ParamToken,
			"stun":        frpxtcp.ParamSTUN,
			"room":        frpxtcp.ParamRoom,
			"secret":      frpxtcp.ParamSecret,
		}
		fs.Visit(func(f *flag.Flag) {
			if key, ok := sugar[f.Name]; ok {
				merged[key] = f.Value.String()
			}
		})
	}
	maps.Copy(merged, explicit)
	return merged
}

func cmdTunnel(args []string) error {
	fs := flag.NewFlagSet("tunnel", flag.ExitOnError)
	backendName := fs.String("backend", frpxtcp.Name, i18n.T("flag.tunnel.backend"))
	params := paramFlags{}
	fs.Var(params, "O", i18n.T("flag.tunnel.param"))
	_, _, verbose := endpointFlags(fs)
	wantPort := fs.Int("port", 0, i18n.T("flag.tunnel.port"))
	// 实测顺利时约 4.8s 就绪（含 STUN 探测与打洞），但重新打洞的慢路径
	// 会明显更久，12s 都可能擦边。玩家此刻已经在中转连接上正常游戏，
	// 这段等待是后台进行的，放宽比误判失败更划算。
	//
	// 这些时间参数全部可覆盖：不同玩家网络差异很大，
	// mod 侧的配置文件会把玩家设定的值经这里传进来。
	d := config.DefaultTimings()
	timeout := fs.Float64("timeout", d.PunchTimeout.Seconds(), i18n.T("flag.tunnel.timeout"))
	probeInterval := fs.Float64("probe-interval", d.ProbeInterval.Seconds(), i18n.T("flag.tunnel.probeInterval"))
	probeTimeout := fs.Float64("probe-timeout", d.ProbeTimeout.Seconds(), i18n.T("flag.tunnel.probeTimeout"))
	retryInterval := fs.Float64("retry-interval", d.RetryMinInterval.Seconds(), i18n.T("flag.tunnel.retryInterval"))
	maxRetries := fs.Int("max-retries-hour", d.MaxRetriesAnHour, i18n.T("flag.tunnel.maxRetries"))
	logPath := fs.String("log-file", "", i18n.T("flag.tunnel.logFile"))
	if err := fs.Parse(args); err != nil {
		return err
	}

	b, ok := backend.Lookup(*backendName)
	if !ok {
		err := i18n.Errorf("tunnel.unknownBackend",
			*backendName, strings.Join(backend.Names(), ", "))
		emit(failedEvent(failureStageStart, failureCodeBackendUnknown, err.Error()))
		return err
	}
	merged := mergedParams(fs, *backendName, params)

	// 默认自动分配端口：玩家本机可能自己开着 25565，
	// 固定端口会撞车，而 mod 是从状态输出里读端口的，用哪个都无所谓。
	port, err := pickPort(*wantPort)
	if err != nil {
		emit(failedEvent(failureStageStart, failureCodeBindPortFailed, err.Error()))
		return err
	}

	if *logPath == "" {
		*logPath = filepath.Join(os.TempDir(), "netherway-tunnel.log")
	}

	// 诊断信息走 stderr：stdout 是 JSON 契约的专用通道，
	// 而 mod 会消费 stderr 并把内容转进游戏日志，这里多说无害。
	diagf := func(format string, args ...any) {
		fmt.Fprintf(os.Stderr, format+"\n", args...)
	}
	diagf("%s", i18n.T("tunnel.diagParams",
		b.Name(), port, strings.Join(sortedKeys(merged), ", ")))
	diagf("%s", i18n.T("tunnel.diagTimings",
		*timeout, *probeInterval, *probeTimeout, *logPath, logLevelOf(*verbose)))

	ctx, stop := signalContext()
	defer stop()

	started := time.Now()
	emit(event{Event: "starting", Backend: b.Name(), Port: port})

	// NAT 分类在后台进行，谁都不等它：终态事件发出时已探得就带上，
	// 没探得就省略。stun 参数与 backend 用的是同一份（缺省同样回落到
	// 构建期默认值），分类结果只作遥测维度。
	natCh := make(chan string, 1)
	go func() {
		natCh <- probeNat(merged[frpxtcp.ParamSTUN], diagf)
	}()
	takeNat := func() string {
		select {
		case v := <-natCh:
			return v
		default:
			return ""
		}
	}

	secs := func(v float64) time.Duration { return time.Duration(v * float64(time.Second)) }
	timings := config.Timings{
		PunchTimeout:     secs(*timeout),
		ProbeInterval:    secs(*probeInterval),
		ProbeTimeout:     secs(*probeTimeout),
		RetryMinInterval: secs(*retryInterval),
		MaxRetriesAnHour: *maxRetries,
	}.Normalize()

	// stderr 回显外再包一层健康扫描：READY 之后 frp 自检持续失败时
	// 经 degradedCh 通知主循环发 degraded 事件（见 health.go）。
	degradedCh := make(chan struct{}, 1)
	health := newTunnelHealthScanner(os.Stderr, degradedCh)

	tunnelErr := make(chan error, 1)
	go func() {
		tunnelErr <- b.Run(ctx, merged, backend.Options{
			BindAddr: "127.0.0.1",
			BindPort: port,
			Timings:  timings,
			LogLevel: logLevelOf(*verbose),
			LogTo:    *logPath,
			Logf:     diagf,
			// frp info 及以上的日志回显到 stderr，经 mod 进游戏日志——
			// 「xtcp server 不存在」这类只有 frp 知道的失败原因不再只躺在文件里
			LogEcho: health,
		})
	}()

	// 没有兜底通道，所以探测成功就一定意味着隧道真的建成了——
	// 这正是 backend 接口禁止自带 fallback 的原因。
	deadline := time.Now().Add(timings.PunchTimeout)
	ready := make(chan *mcping.Status, 1)
	rttCh := make(chan time.Duration, 1)
	probeErr := make(chan error, 1)
	go func() {
		st, rtt, err := mcping.WaitReady("127.0.0.1", port, deadline,
			timings.ProbeInterval, timings.ProbeTimeout)
		if err != nil {
			probeErr <- err
			return
		}
		rttCh <- rtt
		ready <- st
	}()

	select {
	case err := <-tunnelErr:
		reason := i18n.T("tunnel.exitedEarly")
		if err != nil {
			reason = err.Error()
		}
		ev := failedEvent(failureStageBackend, failureCodeBackendExited, reason)
		ev.Nat = takeNat()
		emit(ev)
		return fmt.Errorf("%s", reason)

	case err := <-probeErr:
		ev := failedEvent(failureStageProbe, failureCodeReadyProbeTimeout,
			i18n.T("tunnel.probeFailed", err))
		ev.Nat = takeNat()
		emit(ev)
		return i18n.Errorf("tunnel.notReadyIn", *timeout)

	case st := <-ready:
		<-rttCh // 首次探测含建链耗时，对比延迟没有意义，丢弃
		e := event{
			Event:     "ready",
			Port:      port,
			ElapsedMs: time.Since(started).Milliseconds(),
		}
		if st != nil {
			e.Version = st.Version.Name
			e.Online = st.Players.Online
		}
		// 隧道刚打通时的第一条连接会明显偏慢（实测能到 2.4s），
		// 多测几次取最小值才反映真实往返延迟。
		e.RTTMs = measureRTT(port, timings.ProbeTimeout, deadline)
		e.Nat = takeNat()
		emit(e)
		health.arm()
	}

	// 就绪后保持运行，直到 mod 结束这个进程（玩家断开或退出游戏）。
	// 期间 frp 自检连续报告隧道已断（典型原因：服务端重启、密钥轮换，
	// frp 只会拿旧凭证无限重试）时发 degraded。事件是建议性的，进程
	// 不退出：frp 仍可能自愈，mod 正承载连接时也会选择不动。
	for {
		select {
		case <-ctx.Done():
			emit(event{Event: "stopped"})
			return nil
		case err := <-tunnelErr:
			emit(event{Event: "stopped", Reason: fmt.Sprint(err)})
			return err
		case <-degradedCh:
			diagf("%s", i18n.T("tunnel.degraded"))
			emit(event{Event: "degraded", Port: port})
		}
	}
}
