// Package tunnel 把 frpc 作为库嵌入，避免额外进程和 toml 配置文件。
package tunnel

import (
	"context"
	"fmt"
	"os"

	"github.com/fatedier/frp/client"
	"github.com/fatedier/frp/pkg/config/source"
	v1 "github.com/fatedier/frp/pkg/config/v1"
	"github.com/fatedier/frp/pkg/config/v1/validation"
	frplog "github.com/fatedier/frp/pkg/util/log"
	"github.com/samber/lo"

	"github.com/ripplecraft/xtcpinmc/internal/config"
)

// Endpoint 描述 frps 的位置和鉴权，两端相同。
type Endpoint struct {
	ServerAddr string
	ServerPort int
	Token      string
	STUNServer string
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

func commonConfig(ep Endpoint, logOpts LogOptions) (*v1.ClientCommonConfig, error) {
	log := logOpts.orDefault()

	// 必须显式初始化：client.NewService 不会读 Log 配置去建日志器，
	// 不调这一步 frp 会一直往 stdout 打日志，
	// 而 tunnel 子命令的 stdout 是留给 mod 解析 JSON 的。
	frplog.InitLogger(log.To, log.Level, 7, true)

	c := &v1.ClientCommonConfig{
		ServerAddr:        ep.ServerAddr,
		ServerPort:        ep.ServerPort,
		NatHoleSTUNServer: ep.STUNServer,
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

// Serve 在 Minecraft 宿主机运行：把本地端口注册为 xtcp（P2P）代理，
// 并同时注册一个 stcp 代理供打洞失败时兜底。
func Serve(ctx context.Context, ep Endpoint, room config.Room, localPort int, log LogOptions) error {
	common, err := commonConfig(ep, log)
	if err != nil {
		return err
	}

	xtcp := &v1.XTCPProxyConfig{
		ProxyBaseConfig: v1.ProxyBaseConfig{
			Name: room.ProxyName(),
			Type: string(v1.ProxyTypeXTCP),
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
