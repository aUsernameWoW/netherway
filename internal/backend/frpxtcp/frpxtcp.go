// Package frpxtcp 是 frp xtcp 打洞的 backend 实现。
package frpxtcp

import (
	"context"
	"fmt"
	"strconv"

	"github.com/ripplecraft/xtcpinmc/internal/backend"
	"github.com/ripplecraft/xtcpinmc/internal/config"
	"github.com/ripplecraft/xtcpinmc/internal/stunpick"
	"github.com/ripplecraft/xtcpinmc/internal/tunnel"
)

// Name 是本 backend 在凭证与命令行中的标识。
const Name = "frp-xtcp"

// 参数键名——服务端下发的凭证与命令行 -O 用的都是这些名字，
// 与 Java 侧 Credentials.frpXtcp 必须保持一致。
const (
	ParamServer     = "server"
	ParamServerPort = "serverPort"
	ParamToken      = "token"
	ParamSTUN       = "stun"
	ParamRoom       = "room"
	ParamSecret     = "secret"
)

type impl struct{}

// New 返回 frp xtcp backend。
func New() backend.Backend { return impl{} }

func (impl) Name() string { return Name }

func (impl) Run(ctx context.Context, params map[string]string, opts backend.Options) error {
	// 缺省参数用构建期注入的默认值补齐，保持「零配置可用」；
	// 无法识别的键按接口契约直接忽略。
	ep := tunnel.Endpoint{
		ServerAddr: paramOr(params, ParamServer, config.DefaultServerAddr),
		ServerPort: config.ServerPortDefault(),
		Token:      paramOr(params, ParamToken, config.DefaultToken),
		STUNServer: paramOr(params, ParamSTUN, config.DefaultSTUNServer),
	}
	if v, ok := params[ParamServerPort]; ok && v != "" {
		p, err := strconv.Atoi(v)
		if err != nil || p <= 0 || p > 65535 {
			return fmt.Errorf("参数 %s 非法: %q", ParamServerPort, v)
		}
		ep.ServerPort = p
	}
	room := config.Room{
		Name:      paramOr(params, ParamRoom, config.DefaultRoom),
		SecretKey: paramOr(params, ParamSecret, config.DefaultSecretKey),
	}
	if err := ep.Validate(); err != nil {
		return err
	}
	if err := room.Validate(); err != nil {
		return err
	}

	// 先当场验证一个可用的 STUN 再启动 frp：frp 只认单个地址，
	// 押在一台上会因它的偶发抖动而白白失去一次升级机会。
	picked, err := stunpick.Resolve(ep.STUNServer, nil)
	if err != nil {
		return err
	}
	ep.STUNServer = picked

	return tunnel.Join(ctx, ep, room, tunnel.JoinOptions{
		BindAddr: opts.BindAddr,
		BindPort: opts.BindPort,
		// 接口契约：backend 不得自带兜底，否则就绪探测分不清打没打通
		NoFallback: true,
		Timings:    opts.Timings,
	}, tunnel.LogOptions{Level: opts.LogLevel, To: opts.LogTo})
}

func paramOr(params map[string]string, key, fallback string) string {
	if v, ok := params[key]; ok && v != "" {
		return v
	}
	return fallback
}
