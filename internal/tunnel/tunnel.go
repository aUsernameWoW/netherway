// Package tunnel 把 frpc 作为库嵌入，避免额外进程和 toml 配置文件。
package tunnel

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"os"

	"github.com/fatedier/frp/client"
	"github.com/fatedier/frp/pkg/config/source"
	v1 "github.com/fatedier/frp/pkg/config/v1"
	"github.com/fatedier/frp/pkg/config/v1/validation"
	frplog "github.com/fatedier/frp/pkg/util/log"
	golog "github.com/fatedier/golib/log"
	"github.com/samber/lo"

	"github.com/ripplecraft/xtcpinmc/internal/config"
)

// Endpoint 描述 frps 的位置和鉴权，两端相同。
type Endpoint struct {
	ServerAddr string
	ServerPort int
	Token      string
	STUNServer string
	// Metas 随 Login 一起发给 frps，供 authplugin 做身份校验
	// （玩家侧 user/token，serve 侧静态令牌）。身份刻意不用 frp 的 User
	// 字段：它会给 visitor 的目标代理名加前缀，metas 对命名零影响。
	Metas map[string]string
}

// Validate 检查必填字段。默认值经构建期注入，这里为空说明既没注入也没传参。
func (ep Endpoint) Validate() error {
	if ep.ServerAddr == "" {
		return fmt.Errorf("未指定 frps 地址：用 -server 指定，或在构建时注入")
	}
	if ep.Token == "" {
		return fmt.Errorf("未指定 frps 令牌：用 -token 指定，或在构建时注入")
	}
	return nil
}

// LogOptions 控制 frp 自身日志的去向。
//
// 被 mod 作为子进程调用时，stdout 要留给结构化状态输出，
// 这里就得把 frp 日志改写到文件，否则两者混在一起没法解析。
type LogOptions struct {
	Level string // trace/debug/info/warn/error
	To    string // "console" 或文件路径
	// Echo 非 nil 时额外收到一份 info 及以上级别的日志副本（仅文件模式生效）。
	// tunnel 模式把它指向 stderr：frp 报的错（比如「xtcp server 不存在」，
	// 说明宿主机的 serve 没在运行）就能被 mod 转进游戏日志，玩家不用去翻
	// 日志文件。debug 不回显——隧道存活期间会持续刷屏，详情留在文件里。
	Echo io.Writer
}

func (l LogOptions) orDefault() LogOptions {
	if l.Level == "" {
		l.Level = "info"
	}
	if l.To == "" {
		l.To = "console"
	}
	return l
}

// initFrpLogger 初始化 frp 的全局日志器。
//
// 必须显式初始化：client.NewService 不会读 Log 配置去建日志器，
// 不调这一步 frp 会一直往 stdout 打日志，
// 而 tunnel 子命令的 stdout 是留给 mod 解析 JSON 的。
//
// 文件模式不走 frplog.InitLogger（它只支持单一去向），自行组装 writer
// 以便同时回显到 Echo。轮转参数与 InitLogger 保持一致（按天、留 7 天）。
func initFrpLogger(log LogOptions) {
	if log.To == "console" {
		frplog.InitLogger(log.To, log.Level, 7, true)
		return
	}
	file := golog.NewRotateFileWriter(golog.RotateFileConfig{
		FileName: log.To,
		Mode:     golog.RotateFileModeDaily,
		MaxDays:  7,
	})
	file.Init()
	var out io.Writer = file
	if log.Echo != nil {
		out = teeWriter{file: file, echo: log.Echo}
	}
	level, err := golog.ParseLevel(log.Level)
	if err != nil {
		level = golog.InfoLevel
	}
	frplog.Logger = frplog.Logger.WithOptions(golog.WithOutput(out), golog.WithLevel(level))
}

// teeWriter 把日志写进文件，并把 info 及以上的行回显给 Echo。
// golib/log 每条日志恰好一次 Write，整行到达，可以按行判级别。
type teeWriter struct {
	file io.Writer
	echo io.Writer
}

func (t teeWriter) Write(p []byte) (int, error) {
	n, err := t.file.Write(p)
	// 行首是定长时间戳（不含方括号），第一个 [X] 就是级别标记
	if i := bytes.IndexByte(p, '['); i >= 0 && i+1 < len(p) {
		switch p[i+1] {
		case 'T', 'D':
			return n, err
		}
	}
	// 回显失败不能影响文件日志，错误直接丢弃
	_, _ = t.echo.Write(p)
	return n, err
}

func commonConfig(ep Endpoint, logOpts LogOptions) (*v1.ClientCommonConfig, error) {
	log := logOpts.orDefault()

	initFrpLogger(log)

	c := &v1.ClientCommonConfig{
		ServerAddr:        ep.ServerAddr,
		ServerPort:        ep.ServerPort,
		NatHoleSTUNServer: ep.STUNServer,
		Metadatas:         ep.Metas,
		LoginFailExit:     lo.ToPtr(false), // 网络抖动时持续重试，而不是退出
		Auth: v1.AuthClientConfig{
			Method: v1.AuthMethodToken,
			Token:  ep.Token,
		},
		Log: v1.LogConfig{
			To:    log.To,
			Level: log.Level,
		},
	}
	if err := c.Complete(); err != nil {
		return nil, fmt.Errorf("补全客户端配置: %w", err)
	}
	return c, nil
}

// run 启动 frpc 并阻塞直到 ctx 取消。
//
// v0.70.0 的 ServiceOptions 不再直接接收配置切片，改为通过 ConfigSource
// 注入；好处是后续可以用 ReplaceAll 热更新代理而无需重启隧道。
func run(ctx context.Context, common *v1.ClientCommonConfig,
	proxies []v1.ProxyConfigurer, visitors []v1.VisitorConfigurer) error {

	// 提前校验，把配置错误变成启动时的明确报错，
	// 而不是等玩家连不上再去翻日志。
	// 末位 nil 表示不启用任何 unsafe 特性，本项目只用 xtcp/stcp，用不到。
	warning, err := validation.ValidateAllClientConfig(common, proxies, visitors, nil)
	if err != nil {
		return fmt.Errorf("配置校验: %w", err)
	}
	if warning != nil {
		fmt.Fprintf(os.Stderr, "配置警告: %v\n", warning)
	}

	cs := source.NewConfigSource()
	if err := cs.ReplaceAll(proxies, visitors); err != nil {
		return fmt.Errorf("装载代理配置: %w", err)
	}

	svc, err := client.NewService(client.ServiceOptions{
		Common:                 common,
		ConfigSourceAggregator: source.NewAggregator(cs),
	})
	if err != nil {
		return fmt.Errorf("创建 frpc 服务: %w", err)
	}
	return svc.Run(ctx)
}

// ServeOptions 是宿主侧的可选参数。
type ServeOptions struct {
	// ProxyProtocol 非空（"v1"/"v2"）时，frpc 在连本地 MC 端口前先发一个
	// PROXY protocol 头，把来访连接的源地址告诉 MC 服务端。
	//
	// 现状（frp v0.70.0）：只有 stcp 中转路径会真的带头（frps 把 visitor
	// 连接的公网地址填进 StartWorkConn.SrcAddr）；xtcp 的 P2P 流没有
	// SrcAddr，配了也静默不发——等上游支持（fatedier/frp#2748）后自动生效。
	// 因此 MC 侧的解析必须是嗅探式的，绝不能要求头必须存在。
	ProxyProtocol string
}

// Serve 在 Minecraft 宿主机运行：把本地端口注册为 xtcp（P2P）代理，
// 并同时注册一个 stcp 代理供打洞失败时兜底。
func Serve(ctx context.Context, ep Endpoint, room config.Room, localPort int,
	opts ServeOptions, log LogOptions) error {
	common, err := commonConfig(ep, log)
	if err != nil {
		return err
	}

	xtcp := &v1.XTCPProxyConfig{
		ProxyBaseConfig: v1.ProxyBaseConfig{
			Name: room.ProxyName(),
			Type: string(v1.ProxyTypeXTCP),
			Transport: v1.ProxyTransport{
				ProxyProtocolVersion: opts.ProxyProtocol,
			},
			ProxyBackend: v1.ProxyBackend{
				LocalIP:   "127.0.0.1",
				LocalPort: localPort,
			},
		},
		// 注意：这个字段名是 Secretkey（小写 k），
		// 而 visitor 侧是 SecretKey（大写 K）。frp 自身的命名不一致。
		Secretkey: room.SecretKey,
	}
	xtcp.Complete()

	stcp := &v1.STCPProxyConfig{
		ProxyBaseConfig: v1.ProxyBaseConfig{
			Name: room.RelayProxyName(),
			Type: string(v1.ProxyTypeSTCP),
			Transport: v1.ProxyTransport{
				ProxyProtocolVersion: opts.ProxyProtocol,
			},
			ProxyBackend: v1.ProxyBackend{
				LocalIP:   "127.0.0.1",
				LocalPort: localPort,
			},
		},
		Secretkey: room.SecretKey,
	}
	stcp.Complete()

	return run(ctx, common, []v1.ProxyConfigurer{xtcp, stcp}, nil)
}

// JoinOptions 是玩家侧监听参数。
type JoinOptions struct {
	// BindAddr 若要配合局域网广播，必须是 0.0.0.0：
	// Minecraft 用广播包的源 IP（网卡地址）去连，而不是 127.0.0.1。
	BindAddr string
	BindPort int
	// NoFallback 关闭 stcp 兜底，只走 P2P。
	//
	// mod 场景下玩家已经通过既有的中转隧道连上服务器了，打洞失败就留在
	// 那条连接上即可，再让 agent 自己开一条中转纯属浪费；而且 stcp 通道
	// 会让「隧道可用」的探测始终成功，反而分不清到底有没有打通。
	NoFallback bool
	// Timings 为零值时使用实测得出的默认值。
	Timings config.Timings
}

// Join 在玩家机器运行：监听本地端口，打洞直连宿主机，
// 打洞未成功时先经 frps 中转，后台继续打洞并在成功后自动升级。
func Join(ctx context.Context, ep Endpoint, room config.Room, opts JoinOptions, log LogOptions) error {
	common, err := commonConfig(ep, log)
	if err != nil {
		return err
	}

	t := opts.Timings.Normalize()
	xtcp := &v1.XTCPVisitorConfig{
		VisitorBaseConfig: v1.VisitorBaseConfig{
			Name:       room.VisitorName(),
			Type:       string(v1.VisitorTypeXTCP),
			ServerName: room.ProxyName(),
			SecretKey:  room.SecretKey,
			BindAddr:   opts.BindAddr,
			BindPort:   opts.BindPort,
		},
		// 提前打洞，避免玩家点进服务器时才开始打洞导致首次连接超时
		KeepTunnelOpen:   true,
		MaxRetriesAnHour: t.MaxRetriesAnHour,
		MinRetryInterval: int(t.RetryMinInterval.Seconds()),
	}

	visitors := []v1.VisitorConfigurer{xtcp}
	if !opts.NoFallback {
		relay := &v1.STCPVisitorConfig{
			VisitorBaseConfig: v1.VisitorBaseConfig{
				Name:       room.RelayVisitorName(),
				Type:       string(v1.VisitorTypeSTCP),
				ServerName: room.RelayProxyName(),
				SecretKey:  room.SecretKey,
				BindAddr:   "127.0.0.1",
				BindPort:   -1, // 不单独监听，仅供 xtcp 回落时内部使用
			},
		}
		relay.Complete()
		// 打洞未在此时限内成功就先走中转，后台继续打洞，成功后下条连接自动升级
		xtcp.FallbackTo = room.RelayVisitorName()
		xtcp.FallbackTimeoutMs = int(t.FallbackTimeout.Milliseconds())
		visitors = append(visitors, relay)
	}
	xtcp.Complete()

	return run(ctx, common, nil, visitors)
}
