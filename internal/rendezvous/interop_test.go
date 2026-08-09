package rendezvous

// 这个文件用 frp 的 client 库真连内嵌会合点，钉住登录与代理注册的行为契约。
//
// 为什么值得单独一套：frp 经 dependabot 自动合入 bump，编译不过会被 CI
// 拦下，但「编译过而行为变」不会——插件先于 token 校验的顺序、metas 的
// 传递、NewProxy 的拒绝路径都属于这一类。绑定范围那几条测试保证会合点
// 「只开对的口」，这里保证「开的口按说好的规则放行」。
//
// 全程回环、不出网：STUN 只在真正打洞时才会被联系，代理注册走不到那一步。
//
// 别给这些测试开 -race：frp v0.70 自身的关停路径就带数据竞争（client 的
// stop↔keepControllerWorking、server 的 worker↔RegisterWorkConn），frpc
// 成功登录后被取消就会触发，属上游问题，与测试写法无关。CI 也因此不开。

import (
	"context"
	"testing"
	"time"

	"github.com/fatedier/frp/client"
	frpproxy "github.com/fatedier/frp/client/proxy"
	"github.com/fatedier/frp/pkg/config/source"
	v1 "github.com/fatedier/frp/pkg/config/v1"
	"github.com/samber/lo"

	"github.com/aUsernameWoW/netherway/internal/authplugin"
)

const (
	itToken      = "it-global-token"
	itSigningKey = "it-signing-key"
	itStatic     = "it-serve-static-token"
	itProxy      = "it-room.p2p"
)

// startAuthedRendezvous 起一个开了每玩家令牌校验的会合点，AllowLegacy 关。
func startAuthedRendezvous(t *testing.T) (*Service, int) {
	t.Helper()
	port := freePort(t)
	svc, err := Start(t.Context(), Options{
		BindPort:     port,
		Token:        itToken,
		SigningKey:   itSigningKey,
		StaticTokens: []string{itStatic},
	})
	if err != nil {
		t.Fatalf("启动会合点: %v", err)
	}
	t.Cleanup(svc.Close)
	return svc, port
}

// dialClient 按 tunnel.Serve 同款布局组装一个 frpc 连上会合点，注册一个
// xtcp 代理。LoginFailExit 恒为真：登录被拒时 Run 立刻带错返回，
// 测试就能明确区分「登录被拒」与「登录成功但注册被拒」。
func dialClient(t *testing.T, port int, token string, metas map[string]string) (*client.Service, <-chan error) {
	t.Helper()
	common := &v1.ClientCommonConfig{
		ServerAddr:    "127.0.0.1",
		ServerPort:    port,
		Metadatas:     metas,
		LoginFailExit: lo.ToPtr(true),
		Auth: v1.AuthClientConfig{
			Method: v1.AuthMethodToken,
			Token:  token,
		},
	}
	if err := common.Complete(); err != nil {
		t.Fatalf("补全客户端配置: %v", err)
	}

	xtcp := &v1.XTCPProxyConfig{
		ProxyBaseConfig: v1.ProxyBaseConfig{
			Name: itProxy,
			Type: string(v1.ProxyTypeXTCP),
			ProxyBackend: v1.ProxyBackend{
				LocalIP:   "127.0.0.1",
				LocalPort: 25565,
			},
		},
		Secretkey: "it-secret",
	}
	xtcp.Complete()

	cs := source.NewConfigSource()
	if err := cs.ReplaceAll([]v1.ProxyConfigurer{xtcp}, nil); err != nil {
		t.Fatalf("装载代理配置: %v", err)
	}
	svc, err := client.NewService(client.ServiceOptions{
		Common:                 common,
		ConfigSourceAggregator: source.NewAggregator(cs),
	})
	if err != nil {
		t.Fatalf("创建 frpc 服务: %v", err)
	}

	ctx, cancel := context.WithCancel(t.Context())
	t.Cleanup(cancel)
	errCh := make(chan error, 1)
	go func() { errCh <- svc.Run(ctx) }()
	return svc, errCh
}

// waitProxyPhase 轮询代理状态直到进入期望阶段。期望的是拒绝（start error）
// 时，一旦看到 running 立即失败——那说明 NewProxy 把不该放的放进来了。
func waitProxyPhase(t *testing.T, svc *client.Service, want string, errCh <-chan error) {
	t.Helper()
	deadline := time.After(20 * time.Second)
	tick := time.NewTicker(50 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case err := <-errCh:
			t.Fatalf("frpc 提前退出（登录被拒？）: %v", err)
		case <-deadline:
			st, ok := svc.StatusExporter().GetProxyStatus(itProxy)
			if ok {
				t.Fatalf("等待代理进入 %q 超时，当前 %q（%s）", want, st.Phase, st.Err)
			}
			t.Fatalf("等待代理进入 %q 超时，且查不到代理状态", want)
		case <-tick.C:
		}
		st, ok := svc.StatusExporter().GetProxyStatus(itProxy)
		if !ok {
			continue
		}
		if st.Phase == want {
			return
		}
		if want != frpproxy.ProxyPhaseRunning && st.Phase == frpproxy.ProxyPhaseRunning {
			t.Fatalf("代理意外进入 running：NewProxy 竟放行了非静态令牌")
		}
	}
}

// waitLoginRejected 等 frpc 因登录被拒而退出。
func waitLoginRejected(t *testing.T, errCh <-chan error) {
	t.Helper()
	select {
	case err := <-errCh:
		if err == nil {
			t.Fatal("frpc 无错退出，期望的是登录被拒")
		}
	case <-time.After(20 * time.Second):
		t.Fatal("20 秒内登录未被拒绝——插件是不是没被问到？")
	}
}

// TestStaticTokenRegistersProxy 是 serve 自己的路：静态令牌过 Login 与
// NewProxy 两关，xtcp 代理注册成功。整条链路（TLS 控制通道 → frps token
// 校验 → HTTP 插件）与生产 serveEmbedded 完全一致。
func TestStaticTokenRegistersProxy(t *testing.T) {
	_, port := startAuthedRendezvous(t)
	svc, errCh := dialClient(t, port, itToken, map[string]string{
		authplugin.MetaToken: itStatic,
	})
	waitProxyPhase(t, svc, frpproxy.ProxyPhaseRunning, errCh)
}

// TestPlayerTokenCannotRegisterProxy 是玩家侧的边界：每玩家令牌足以登录，
// 但注册代理必须被拒——玩家侧只有 visitor，任何注册代理的企图都不是正常流量。
// 进入 start error（而非 frpc 带错退出）同时也证明了登录这一关是过了的。
func TestPlayerTokenCannotRegisterProxy(t *testing.T) {
	_, port := startAuthedRendezvous(t)
	user := "player-uuid-1"
	issued := authplugin.IssueToken(itSigningKey, user, time.Now().Add(time.Hour).Unix())
	svc, errCh := dialClient(t, port, itToken, map[string]string{
		authplugin.MetaUser:  user,
		authplugin.MetaToken: issued,
	})
	waitProxyPhase(t, svc, frpproxy.ProxyPhaseStartErr, errCh)
}

// TestLegacyLoginRejected：没带 metas 的登录（老 mod agent）在
// AllowLegacy 关闭时必须被拒。这条同时钉住「插件在 frps 的 token 校验
// 之前执行」——全局 token 是对的，拒绝只能来自插件。
func TestLegacyLoginRejected(t *testing.T) {
	_, port := startAuthedRendezvous(t)
	_, errCh := dialClient(t, port, itToken, nil)
	waitLoginRejected(t, errCh)
}

// TestWrongGlobalTokenRejected：静态令牌有效但全局 token 不对，仍须被拒。
// 插件与 frps 自身的 token 校验是叠加关系，两层都过才放行。
func TestWrongGlobalTokenRejected(t *testing.T) {
	_, port := startAuthedRendezvous(t)
	_, errCh := dialClient(t, port, "wrong-global-token", map[string]string{
		authplugin.MetaToken: itStatic,
	})
	waitLoginRejected(t, errCh)
}
