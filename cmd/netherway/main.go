// netherway 通过 frp xtcp 打洞，让玩家 P2P 直连 Minecraft 服务器。
//
//	netherway serve    在服务器宿主机运行
//	netherway tunnel   供 Minecraft mod 调用，打洞并输出逐行 JSON 状态
//	netherway authplugin  在 frps 宿主机运行（每玩家令牌校验）
//
// 玩家进服前的凭证预取不在这里：它是 mod 与 MC 服务端之间在 Minecraft
// 端口上的一次对话（core 的 PreauthClient/PreauthService），不经 agent，
// 也不需要服务器多开任何监听端口。
package main

import (
	"context"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/aUsernameWoW/netherway/internal/backend"
	"github.com/aUsernameWoW/netherway/internal/config"
	"github.com/aUsernameWoW/netherway/internal/i18n"
)

// Mirrors of frpxtcp.Name / goncp2p.Name. Redeclared as literals so that a
// variant build (-tags nofrp / nogonc) can still name the excluded backend
// in dispatch and error messages without linking its implementation package.
// TestBackendNameMirrors pins them to the real constants.
const (
	frpXtcpName = "frp-xtcp"
	goncP2pName = "gonc-p2p"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "serve":
		err = cmdServe(os.Args[2:])
	case "tunnel":
		err = cmdTunnel(os.Args[2:])
	case "authplugin":
		err = cmdAuthPlugin(os.Args[2:])
	case "-h", "--help", "help":
		usage()
		return
	default:
		fmt.Fprintf(os.Stderr, "%s\n\n", i18n.T("main.unknownCommand", os.Args[1]))
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "%s\n", i18n.T("main.error", err))
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprint(os.Stderr, i18n.T("main.usage"))
}

// endpointOpts 收集 frp 经典模式的连接选项。刻意是纯数据结构而非
// tunnel.Endpoint：main.go 在所有变体下都要编译，不能 import frp。
type endpointOpts struct {
	ServerAddr string
	ServerPort int
	Token      string
	STUNServer string
}

// endpointFlags 注册两端共用的选项。gonc-only 变体下这些旗标仍然注册
// （帮助文本与旧调用方保持稳定），只是 frp 相关取值不再被消费。
func endpointFlags(fs *flag.FlagSet) (*endpointOpts, *config.Room, *bool) {
	ep := &endpointOpts{}
	room := &config.Room{}
	fs.StringVar(&ep.ServerAddr, "server", config.DefaultServerAddr, i18n.T("flag.server"))
	fs.IntVar(&ep.ServerPort, "server-port", config.ServerPortDefault(), i18n.T("flag.serverPort"))
	fs.StringVar(&ep.Token, "token", config.DefaultToken, i18n.T("flag.token"))
	fs.StringVar(&ep.STUNServer, "stun", config.DefaultSTUNServer, i18n.T("flag.stun"))
	fs.StringVar(&room.Name, "room", config.DefaultRoom, i18n.T("flag.room"))
	fs.StringVar(&room.SecretKey, "secret", config.DefaultSecretKey, i18n.T("flag.secret"))
	verbose := fs.Bool("v", false, i18n.T("flag.verbose"))
	return ep, room, verbose
}

func logLevelOf(verbose bool) string {
	if verbose {
		return "debug"
	}
	return "info"
}

// signalContext 返回一个在收到 SIGINT/SIGTERM 时取消的 context。
func signalContext() (context.Context, context.CancelFunc) {
	return signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
}

// checkProxyProtocol 在 backend 的配置校验之前给句明白话。
func checkProxyProtocol(v string) error {
	switch v {
	case "", "v1", "v2":
		return nil
	default:
		return i18n.Errorf("serve.badProxyProtocol", v)
	}
}

// backendNotBuilt 是变体裁剪掉的 backend 的统一拒绝话术：说清是构建
// 不含，而不是名字打错（那是 unknownBackend 的语义）。
func backendNotBuilt(name string) error {
	return i18n.Errorf("main.backendNotBuilt",
		name, strings.Join(backend.Names(), ", "))
}

func cmdServe(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	ep, room, verbose := endpointFlags(fs)
	localPort := fs.Int("port", 25565, i18n.T("flag.serve.port"))
	metaToken := fs.String("meta-token", "", i18n.T("flag.serve.metaToken"))
	proxyProtocol := fs.String("proxy-protocol", "", i18n.T("flag.serve.proxyProtocol"))
	rendezvousPort := fs.Int("rendezvous", 0, i18n.T("flag.serve.rendezvous"))
	signingKey := fs.String("signing-key", "", i18n.T("flag.serve.signingKey"))
	backendName := fs.String("backend", defaultBackendName, i18n.T("flag.serve.backend"))
	params := paramFlags{}
	fs.Var(params, "O", i18n.T("flag.serve.param"))
	if err := fs.Parse(args); err != nil {
		return err
	}
	// serve 的两条路径差异太大（frp 是 frpc+可选内嵌 frps，gonc 是自己的
	// wait 循环），不走 backend.Lookup 而按名字分派；变体构建下被裁掉的
	// 那侧由 stub 报「构建不含」。
	switch *backendName {
	case goncP2pName:
		return serveGonc(params, *localPort, *rendezvousPort, *proxyProtocol)
	case frpXtcpName:
		return serveFrp(ep, room, *localPort, *rendezvousPort,
			*signingKey, *metaToken, *proxyProtocol, *verbose)
	default:
		return i18n.Errorf("tunnel.unknownBackend",
			*backendName, strings.Join(backend.Names(), ", "))
	}
}

// pickPort 优先使用 want，被占用时让系统分配一个空闲端口。
// tunnel 模式会把实际端口随 STARTING 事件上报，用哪个都不影响调用方。
func pickPort(want int) (int, error) {
	if want > 0 && portFree(want) {
		return want, nil
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, i18n.Errorf("main.noFreePort", err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}

func portFree(port int) bool {
	l, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", port))
	if err != nil {
		return false
	}
	l.Close()
	return true
}
