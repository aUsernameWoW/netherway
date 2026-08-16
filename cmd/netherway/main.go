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
	"syscall"

	"github.com/aUsernameWoW/netherway/internal/config"
	"github.com/aUsernameWoW/netherway/internal/i18n"
	"github.com/aUsernameWoW/netherway/internal/stunpick"
	"github.com/aUsernameWoW/netherway/internal/tunnel"
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

// endpointFlags 注册两端共用的选项。
func endpointFlags(fs *flag.FlagSet) (*tunnel.Endpoint, *config.Room, *bool) {
	ep := &tunnel.Endpoint{}
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

func validate(ep *tunnel.Endpoint, room *config.Room) error {
	if err := ep.Validate(); err != nil {
		return err
	}
	return room.Validate()
}

func logLevelOf(verbose bool) string {
	if verbose {
		return "debug"
	}
	return "info"
}

// consoleLog 是 serve 这类前台命令的日志配置。
func consoleLog(verbose bool) tunnel.LogOptions {
	return tunnel.LogOptions{Level: logLevelOf(verbose), To: "console"}
}

// signalContext 返回一个在收到 SIGINT/SIGTERM 时取消的 context。
func signalContext() (context.Context, context.CancelFunc) {
	return signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
}

func cmdServe(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	ep, room, verbose := endpointFlags(fs)
	localPort := fs.Int("port", 25565, i18n.T("flag.serve.port"))
	metaToken := fs.String("meta-token", "", i18n.T("flag.serve.metaToken"))
	proxyProtocol := fs.String("proxy-protocol", "", i18n.T("flag.serve.proxyProtocol"))
	rendezvousPort := fs.Int("rendezvous", 0, i18n.T("flag.serve.rendezvous"))
	signingKey := fs.String("signing-key", "", i18n.T("flag.serve.signingKey"))
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *rendezvousPort != 0 {
		return serveEmbedded(ep, room, *localPort, *rendezvousPort,
			*signingKey, *metaToken, *proxyProtocol, *verbose)
	}
	if err := validate(ep, room); err != nil {
		return err
	}
	if err := checkProxyProtocol(*proxyProtocol); err != nil {
		return err
	}
	if *metaToken != "" {
		ep.Metas = map[string]string{"token": *metaToken}
	}

	ctx, stop := signalContext()
	defer stop()

	fmt.Println(i18n.T("serve.publish", *localPort, room.Name))
	picked, err := stunpick.Resolve(ep.STUNServer, func(f string, a ...any) {
		fmt.Printf(f+"\n", a...)
	})
	if err != nil {
		return err
	}
	ep.STUNServer = picked
	fmt.Printf("frps %s:%d\n", ep.ServerAddr, ep.ServerPort)
	if *proxyProtocol != "" {
		fmt.Println(i18n.T("serve.proxyProtocolOn", *proxyProtocol))
	}
	return tunnel.Serve(ctx, *ep, *room, *localPort,
		tunnel.ServeOptions{ProxyProtocol: *proxyProtocol}, consoleLog(*verbose))
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
