// Package frpxtcp 是 frp xtcp 打洞的 backend 实现。
package frpxtcp

import (
	"context"
	"fmt"
	"sort"
	"strconv"
	"strings"

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
	// ParamUser / ParamUserToken 是每玩家身份（frps 侧 authplugin 校验），
	// 经 frp 的 metadatas 随登录发出。服务端未启用签发时不下发，可缺省。
	ParamUser      = "user"
	ParamUserToken = "userToken"
)

type impl struct{}

// New 返回 frp xtcp backend。
func New() backend.Backend { return impl{} }

func (impl) Name() string { return Name }

func (impl) Run(ctx context.Context, params map[string]string, opts backend.Options) error {
	// 无法识别的键按接口契约必须忽略，但要说出来：服务端配置里键名拼错时
	// （比如把 secret 写成 key），这是唯一能暴露「值被静默丢弃」的地方。
	if unknown := unknownKeys(params); len(unknown) > 0 {
		opts.Diagf("忽略未知参数键 %v（frp-xtcp 认识的键: %s）",
			unknown, strings.Join(knownKeys(), ", "))
	}

	// 缺省参数用构建期注入的默认值补齐，保持「零配置可用」。
	ep := tunnel.Endpoint{
		ServerAddr: paramOr(params, ParamServer, config.DefaultServerAddr),
		ServerPort: config.ServerPortDefault(),
		Token:      paramOr(params, ParamToken, config.DefaultToken),
		STUNServer: paramOr(params, ParamSTUN, config.DefaultSTUNServer),
	}
	// 键名与 internal/authplugin 的 MetaUser/MetaToken 对应
	if u, t := params[ParamUser], params[ParamUserToken]; u != "" || t != "" {
		ep.Metas = map[string]string{}
		if u != "" {
			ep.Metas["user"] = u
		}
		if t != "" {
			ep.Metas["token"] = t
		}
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
	// 生效值的快照。token 与密钥只报有无和长度，值本身绝不能出现在
	// 诊断输出里——这些行最终会进玩家的游戏日志。
	// user 是玩家自己的 UUID，进他自己的日志无妨，明文有助排查。
	opts.Diagf("生效参数: %s=%s %s=%d %s=%s %s=%s %s=%s %s=%s %s=%s %s=%s",
		ParamServer, ep.ServerAddr, ParamServerPort, ep.ServerPort,
		ParamSTUN, ep.STUNServer, ParamRoom, room.Name,
		ParamToken, presence(ep.Token), ParamSecret, presence(room.SecretKey),
		ParamUser, paramOr(params, ParamUser, "空"),
		ParamUserToken, presence(params[ParamUserToken]))
	if err := ep.Validate(); err != nil {
		return withParamKeys(err, params)
	}
	if err := room.Validate(); err != nil {
		return withParamKeys(err, params)
	}

	// 先当场验证一个可用的 STUN 再启动 frp：frp 只认单个地址，
	// 押在一台上会因它的偶发抖动而白白失去一次升级机会。
	picked, err := stunpick.Resolve(ep.STUNServer, opts.Logf)
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
	}, tunnel.LogOptions{Level: opts.LogLevel, To: opts.LogTo, Echo: opts.LogEcho})
}

func paramOr(params map[string]string, key, fallback string) string {
	if v, ok := params[key]; ok && v != "" {
		return v
	}
	return fallback
}

func knownKeys() []string {
	return []string{ParamServer, ParamServerPort, ParamToken,
		ParamSTUN, ParamRoom, ParamSecret, ParamUser, ParamUserToken}
}

func unknownKeys(params map[string]string) []string {
	known := map[string]bool{}
	for _, k := range knownKeys() {
		known[k] = true
	}
	var out []string
	for k := range params {
		if !known[k] {
			out = append(out, k)
		}
	}
	sort.Strings(out)
	return out
}

// presence 描述敏感参数的有无与长度，绝不输出值本身。
func presence(v string) string {
	if v == "" {
		return "空"
	}
	return fmt.Sprintf("已设置(%d字节)", len(v))
}

// withParamKeys 把收到的参数键附在校验错误后面。配置侧键名写错时值会被
// 静默忽略，光看「密钥为空」猜不到原因；键名清单能让人当场对出差异。
func withParamKeys(err error, params map[string]string) error {
	if len(params) == 0 {
		return fmt.Errorf("%w（本次未收到任何参数，全部使用构建期默认值）", err)
	}
	keys := make([]string, 0, len(params))
	for k := range params {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return fmt.Errorf("%w（收到的参数键: %s）", err, strings.Join(keys, ", "))
}
