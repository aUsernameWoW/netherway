package rendezvous

import (
	"fmt"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/aUsernameWoW/netherway/internal/i18n"
)

// 文案断言以 zh 目录为基准；en 侧由 internal/i18n 的一致性测试覆盖。
func init() { i18n.Use(i18n.ZH) }

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("找不到空闲端口: %v", err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

func TestValidateRejectsNonLoopback(t *testing.T) {
	cases := []struct {
		name string
		opts Options
		want string
	}{
		{"公网地址", Options{BindAddr: "0.0.0.0", BindPort: 7000}, "只能绑回环"},
		{"具体外网地址", Options{BindAddr: "203.0.113.7", BindPort: 7000}, "只能绑回环"},
		{"端口为零", Options{BindAddr: "127.0.0.1", BindPort: 0}, "端口非法"},
		{"端口越界", Options{BindAddr: "127.0.0.1", BindPort: 70000}, "端口非法"},
		{"地址不是 IP", Options{BindAddr: "localhost", BindPort: 7000}, "地址非法"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			err := c.opts.Validate()
			if err == nil {
				t.Fatalf("期望被拒，却通过了")
			}
			if !strings.Contains(err.Error(), c.want) {
				t.Fatalf("报错未提及 %q: %v", c.want, err)
			}
		})
	}
}

func TestValidateAcceptsLoopback(t *testing.T) {
	for _, addr := range []string{"", "127.0.0.1", "127.0.0.2", "::1"} {
		if err := (Options{BindAddr: addr, BindPort: 7000}).Validate(); err != nil {
			t.Fatalf("回环地址 %q 应被接受: %v", addr, err)
		}
	}
}

func TestNewTokenIsRandom(t *testing.T) {
	a, err := NewToken()
	if err != nil {
		t.Fatalf("生成令牌: %v", err)
	}
	b, err := NewToken()
	if err != nil {
		t.Fatalf("生成令牌: %v", err)
	}
	if a == b {
		t.Fatalf("两次生成的令牌相同，随机性有问题: %s", a)
	}
	if len(a) != 32 {
		t.Fatalf("令牌长度应为 32 个十六进制字符，实为 %d", len(a))
	}
}

// TestStartListensOnlyOnBoundPort 是这个包最要紧的一条：会合点必须只开
// 说好的那一个回环口。多开一个公网口就意味着「服务器对外只剩那一个映射
// 端口」这条设计前提被破坏，而这种回归从功能上是察觉不到的。
// 判据刻意用「能否自己绑上同一个地址端口」，而不是「拨号能不能通」：
// 开发机上常有透明代理（fake-ip 模式）接受任意地址端口的连接，
// 拨号成功根本证明不了对端在监听，这条测试第一版就是这么误报的。
// 绑定检测走 OS，不受代理干扰。
func TestStartListensOnlyOnBoundPort(t *testing.T) {
	port := freePort(t)

	svc, err := Start(t.Context(), Options{BindPort: port})
	if err != nil {
		t.Fatalf("启动会合点: %v", err)
	}
	defer svc.Close()

	// 说好的口要能连上
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 3*time.Second)
	if err != nil {
		t.Fatalf("会合点端口连不上: %v", err)
	}
	conn.Close()

	// 同一个端口在各非回环地址上都应该还能被我们绑上——绑得上就说明
	// 会合点没在那里监听。绑不上（EADDRINUSE）则说明绑定范围失控了。
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		t.Skipf("取不到网卡地址，无法判定绑定范围: %v", err)
	}
	checked := 0
	for _, a := range addrs {
		ipnet, ok := a.(*net.IPNet)
		if !ok || ipnet.IP.IsLoopback() || ipnet.IP.To4() == nil {
			continue
		}
		l, err := net.Listen("tcp", fmt.Sprintf("%s:%d", ipnet.IP.String(), port))
		if err != nil {
			t.Fatalf("会合点疑似占用了非回环地址 %s 的 %d 端口，绑定范围失控: %v",
				ipnet.IP, port, err)
		}
		l.Close()
		checked++
	}
	if checked == 0 {
		t.Skip("本机没有非回环 IPv4 地址，绑定范围无从验证")
	}
}

func TestStartGeneratesTokenWhenEmpty(t *testing.T) {
	svc, err := Start(t.Context(), Options{BindPort: freePort(t)})
	if err != nil {
		t.Fatalf("启动会合点: %v", err)
	}
	defer svc.Close()

	if svc.Token() == "" {
		t.Fatal("未指定令牌时应自动生成一个")
	}
}

func TestStartKeepsGivenToken(t *testing.T) {
	svc, err := Start(t.Context(), Options{BindPort: freePort(t), Token: "钦定令牌"})
	if err != nil {
		t.Fatalf("启动会合点: %v", err)
	}
	defer svc.Close()

	if svc.Token() != "钦定令牌" {
		t.Fatalf("指定的令牌被改掉了: %q", svc.Token())
	}
}

// TestStartWithAuthPluginServesLoopbackEndpoint 确认配了签发密钥时，
// 内嵌的鉴权端点真的起来了且只在回环上。
func TestStartWithAuthPluginServesLoopbackEndpoint(t *testing.T) {
	svc, err := Start(t.Context(), Options{
		BindPort:     freePort(t),
		SigningKey:   "测试签发密钥",
		StaticTokens: []string{"serve-static"},
	})
	if err != nil {
		t.Fatalf("启动会合点: %v", err)
	}
	defer svc.Close()

	if svc.pluginLn == nil {
		t.Fatal("配了签发密钥，鉴权端点却没起来")
	}
	host, _, err := net.SplitHostPort(svc.pluginLn.Addr().String())
	if err != nil {
		t.Fatalf("解析鉴权端点地址: %v", err)
	}
	if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
		t.Fatalf("鉴权端点不在回环上: %s", host)
	}
}

func TestStartWithoutAuthSkipsPlugin(t *testing.T) {
	svc, err := Start(t.Context(), Options{BindPort: freePort(t)})
	if err != nil {
		t.Fatalf("启动会合点: %v", err)
	}
	defer svc.Close()

	if svc.pluginLn != nil {
		t.Fatal("没配签发密钥也没配静态令牌时不该起鉴权端点")
	}
}

func TestCloseIsIdempotent(t *testing.T) {
	svc, err := Start(t.Context(), Options{BindPort: freePort(t), SigningKey: "k"})
	if err != nil {
		t.Fatalf("启动会合点: %v", err)
	}
	svc.Close()
	svc.Close() // 第二次不能 panic
}
