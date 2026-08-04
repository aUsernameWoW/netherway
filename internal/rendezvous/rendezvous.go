// Package rendezvous 把 frps 作为库嵌进 serve 进程，做**只监听回环**的会合点。
//
// 为什么要内嵌：xtcp 打洞里 frps 只干一件事——在两条控制连接之间转发信令
// （地址发现靠外部 STUN，见 internal/stunpick；打洞后的数据流根本不经它）。
// 一个只需要收发 TCP 的会合点没有理由必须待在公网。把它挪到 serve 进程里、
// 让玩家的控制连接经 Minecraft 端口转发进来，公网侧就只剩一条哑 TCP 隧道：
// 不必装插件、不必配环境变量、不必支持 xtcp，换成 nginx stream 或一条 NAT
// 规则都行。玩家因此可以租用他人的 frp 服务，而不必自建。
//
// 与公网 frps 的关键差别在**信任归属**：这个会合点是服务端自己的，
// 玩家凭证由服务端签发，中继看到的只是不透明字节。
//
// 监听面刻意收得很紧：kcp/quic/vhost/dashboard/ssh 全部显式归零，只在
// BindAddr:BindPort 开一个口，且必须是回环地址——它对外的唯一入口是
// Minecraft 端口上的字节转发，绝不能自己再暴露一个公网口。
package rendezvous

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net"
	"net/http"
	"time"

	v1 "github.com/fatedier/frp/pkg/config/v1"
	"github.com/fatedier/frp/server"

	"github.com/aUsernameWoW/netherway/internal/authplugin"
)

// PluginPath 是内嵌 authplugin 的 HTTP 路径。回环上自说自话，取值无所谓，
// 只需两侧一致。
const PluginPath = "/handler"

// pluginOps 是内嵌 authplugin 订阅的操作。
//
// Login 校验每玩家令牌；NewProxy 只放行静态令牌（即 serve 自己）——
// 玩家侧只有 visitor，任何注册代理的企图都不是正常流量。
var pluginOps = []string{"Login", "NewProxy"}

// Options 是会合点的全部配置。
type Options struct {
	// BindAddr 必须是回环地址。留空取 127.0.0.1。
	BindAddr string
	// BindPort 是会合点监听端口。调用方（服务端 mod）挑好再传进来，
	// 因为嗅探器要往这个端口转发，双方得说定同一个数。
	BindPort int

	// Token 是 frps 的基础校验令牌，与玩家凭证里的 token 同源。
	// 留空则每次启动随机生成一个——「重启即轮换」，与 secret=auto 同构。
	Token string

	// SigningKey 非空时启用每玩家令牌校验（内嵌 authplugin）。
	SigningKey string
	// StaticTokens 是获准注册代理的静态令牌，serve 自己用。
	StaticTokens []string
	// AllowLegacy 放行不带令牌的登录。
	AllowLegacy bool

	// Logf 输出会合点自身的日志；nil 表示静默。
	Logf func(format string, args ...any)
}

func (o Options) bindAddr() string {
	if o.BindAddr == "" {
		return "127.0.0.1"
	}
	return o.BindAddr
}

// Validate 检查配置。
func (o Options) Validate() error {
	if o.BindPort <= 0 || o.BindPort > 65535 {
		return fmt.Errorf("会合点端口非法: %d", o.BindPort)
	}
	ip := net.ParseIP(o.bindAddr())
	if ip == nil {
		return fmt.Errorf("会合点地址非法: %q", o.bindAddr())
	}
	// 这条是安全边界不是洁癖：会合点绑到非回环地址就等于在公网多开一个口，
	// 而「服务器对外只剩那一个映射端口」正是整个设计的立足点。
	if !ip.IsLoopback() {
		return fmt.Errorf("会合点只能绑回环地址（收到 %s）："+
			"它的唯一入口是 Minecraft 端口上的字节转发", o.bindAddr())
	}
	return nil
}

// NewToken 生成一个随机令牌。会合点随服务端每次启动轮换令牌，
// 旧凭证自然失效，玩家走一次中转即自动拿到新的。
func NewToken() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("生成会合点令牌: %w", err)
	}
	return hex.EncodeToString(b), nil
}

// Service 是一个已启动的会合点。
type Service struct {
	token      string
	frps       *server.Service
	pluginHTTP *http.Server
	pluginLn   net.Listener
	logf       func(string, ...any)
}

// Token 返回生效中的令牌——调用方要把它放进下发给玩家的凭证里。
func (s *Service) Token() string { return s.token }

func (o Options) logf(format string, args ...any) {
	if o.Logf != nil {
		o.Logf(format, args...)
	}
}

// Start 启动会合点。返回后 frps 已在监听，调用方可以安全地让 frpc 连上来。
//
// 注意 frp 的日志器是全局的：调用方应先初始化好（serve 经
// tunnel.InitLogger），否则 frps 的日志会走 frp 自己的默认去向。
func Start(ctx context.Context, opts Options) (*Service, error) {
	if err := opts.Validate(); err != nil {
		return nil, err
	}
	token := opts.Token
	if token == "" {
		var err error
		if token, err = NewToken(); err != nil {
			return nil, err
		}
		opts.logf("会合点令牌本次启动随机生成（重启即轮换）")
	}

	svc := &Service{token: token, logf: opts.logf}

	cfg := &v1.ServerConfig{
		BindAddr: opts.bindAddr(),
		BindPort: opts.BindPort,
		Auth: v1.AuthServerConfig{
			Method: v1.AuthMethodToken,
			Token:  token,
		},
	}
	// 监听面显式归零。这些字段的零值本来就不开监听，写出来是为了让
	// 「会合点只开一个口」这件事在代码里可读、也防止将来 frp 改默认值。
	cfg.KCPBindPort = 0
	cfg.QUICBindPort = 0
	cfg.VhostHTTPPort = 0
	cfg.VhostHTTPSPort = 0
	cfg.TCPMuxHTTPConnectPort = 0
	cfg.WebServer.Port = 0
	cfg.SSHTunnelGateway.BindPort = 0

	if opts.SigningKey != "" || len(opts.StaticTokens) > 0 {
		addr, err := svc.startPlugin(opts)
		if err != nil {
			return nil, err
		}
		cfg.HTTPPlugins = []v1.HTTPPluginOptions{{
			Name: "netherway-auth",
			Addr: addr,
			Path: PluginPath,
			Ops:  pluginOps,
		}}
	}

	if err := cfg.Complete(); err != nil {
		svc.closePlugin()
		return nil, fmt.Errorf("补全会合点配置: %w", err)
	}
	frps, err := server.NewService(cfg)
	if err != nil {
		svc.closePlugin()
		return nil, fmt.Errorf("创建会合点: %w", err)
	}
	svc.frps = frps

	go frps.Run(ctx)
	opts.logf("会合点已在 %s:%d 监听（仅回环；玩家经 Minecraft 端口转发进来）",
		opts.bindAddr(), opts.BindPort)
	return svc, nil
}

// startPlugin 在回环上起一个只服务本进程的 HTTP 端点，承载 authplugin。
//
// frps 的插件机制只有 HTTP 一种形态（Service 没有导出进程内注册的口子），
// 所以这里必须真起一个监听。端口取 0 由系统分配，绑回环，
// 生命周期跟着会合点走。
func (s *Service) startPlugin(opts Options) (string, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", fmt.Errorf("启动内嵌鉴权端点: %w", err)
	}
	mux := http.NewServeMux()
	mux.Handle(PluginPath, authplugin.NewHandler(authplugin.Config{
		SigningKey:   opts.SigningKey,
		StaticTokens: opts.StaticTokens,
		AllowLegacy:  opts.AllowLegacy,
		Logf:         opts.Logf,
	}))
	s.pluginLn = ln
	s.pluginHTTP = &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}
	go func() {
		// 会合点关闭时 Serve 会返回 ErrServerClosed，属正常路径
		_ = s.pluginHTTP.Serve(ln)
	}()
	if opts.SigningKey != "" {
		opts.logf("内嵌每玩家令牌校验已启用（签发密钥指纹 %s）",
			authplugin.KeyFingerprint(opts.SigningKey))
	}
	return ln.Addr().String(), nil
}

func (s *Service) closePlugin() {
	if s.pluginHTTP != nil {
		_ = s.pluginHTTP.Close()
		s.pluginHTTP = nil
	}
	if s.pluginLn != nil {
		_ = s.pluginLn.Close()
		s.pluginLn = nil
	}
}

// Close 停掉会合点及其鉴权端点。
func (s *Service) Close() {
	if s.frps != nil {
		_ = s.frps.Close()
		s.frps = nil
	}
	s.closePlugin()
	if s.logf != nil {
		s.logf("会合点已停止")
	}
}
