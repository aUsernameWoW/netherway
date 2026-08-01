// netherway 通过 frp xtcp 打洞，让玩家 P2P 直连 Minecraft 服务器。
//
//	netherway serve    在服务器宿主机运行
//	netherway tunnel   供 Minecraft mod 调用，打洞并输出逐行 JSON 状态
//	netherway authplugin  在 frps 宿主机运行（每玩家令牌校验）
//	netherway authbridge  在服务端运行预认证服务（玩家进服前提前下发凭证）
//	netherway prefetch    在玩家机器预拉取凭证（启动器 Pre-launch 调用）
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
	case "authbridge":
		err = cmdAuthBridge(os.Args[2:])
	case "prefetch":
		err = cmdPrefetch(os.Args[2:])
	case "-h", "--help", "help":
		usage()
		return
	default:
		fmt.Fprintf(os.Stderr, "未知子命令: %s\n\n", os.Args[1])
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "错误: %v\n", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprint(os.Stderr, `netherway — Minecraft P2P 直连

用法:
  netherway serve [选项]    在服务器宿主机运行，把本地端口发布为 P2P 代理
  netherway tunnel [选项]   供 Minecraft mod 调用：纯 P2P，超时即放弃
  netherway authplugin [选项]  在 frps 宿主机运行：每玩家令牌校验（frps httpPlugins）
  netherway authbridge [选项]  在服务端运行：预认证服务（玩家进服前提前下发凭证）
  netherway prefetch  [选项]  在玩家机器运行：预拉取凭证（启动器 Pre-launch 用）

公共选项:
  -server  frps 地址        -port    端口
  -token   frps 令牌        -stun    STUN 服务器
  -room    房间名           -secret  房间密钥
  -v       输出调试日志

serve 专有:
  -meta-token  向 frps 的 authplugin 表明身份的静态令牌（authplugin -static-token 同值）
  -proxy-protocol  连本地 MC 端口前先发 PROXY protocol 头（v1/v2），MC 侧需能剥头

tunnel 专有:
  -backend   隧道方案，默认 frp-xtcp
  -O key=value  传给 backend 的参数，可重复；frp-xtcp 也可直接用上面的公共选项
  -timeout   建链超时秒数，默认 15，超时返回非零码
  -log-file  backend 日志路径（stdout 留给逐行 JSON 状态）

authplugin 专有:
  -listen    监听地址，默认 127.0.0.1:7200    -path  HTTP 路径，默认 /handler
  -key       令牌签发密钥（或环境变量 NETHERWAY_AUTH_KEY）
  -static-token  静态令牌白名单，可重复      -allow-legacy  迁移期放行无令牌登录

authbridge 专有:
  -listen    监听地址，默认 127.0.0.1:7201    -authserver  皮肤站 API root
  -key       令牌签发密钥（或环境变量 NETHERWAY_AUTH_KEY）
  -punch-timeout  建议的打洞超时秒数          其余 -server/-room 等同 serve

prefetch 专有:
  -bridge    authbridge 地址                  -authserver  皮肤站 API root
  -token     accessToken（或环境变量 NETHERWAY_ACCESS_TOKEN）
  -uuid      玩家 UUID                        -username  玩家名
  -cache-dir 凭证缓存目录（mod 的 .minecraft/netherway/credentials）

未指定的选项使用构建时注入的默认值。
`)
}

// endpointFlags 注册两端共用的选项。
func endpointFlags(fs *flag.FlagSet) (*tunnel.Endpoint, *config.Room, *bool) {
	ep := &tunnel.Endpoint{}
	room := &config.Room{}
	fs.StringVar(&ep.ServerAddr, "server", config.DefaultServerAddr, "frps 地址")
	fs.IntVar(&ep.ServerPort, "server-port", config.ServerPortDefault(), "frps 端口")
	fs.StringVar(&ep.Token, "token", config.DefaultToken, "frps 令牌")
	fs.StringVar(&ep.STUNServer, "stun", config.DefaultSTUNServer, "STUN 服务器")
	fs.StringVar(&room.Name, "room", config.DefaultRoom, "房间名")
	fs.StringVar(&room.SecretKey, "secret", config.DefaultSecretKey, "房间密钥")
	verbose := fs.Bool("v", false, "输出调试日志")
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
	localPort := fs.Int("port", 25565, "Minecraft 服务器监听的本地端口")
	metaToken := fs.String("meta-token", "",
		"向 frps 的 authplugin 表明身份的静态令牌；frps 未部署 authplugin 时不需要")
	proxyProtocol := fs.String("proxy-protocol", "",
		"连本地 MC 端口前先发 PROXY protocol 头（v1 或 v2），MC 侧需能剥头；留空关闭")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if err := validate(ep, room); err != nil {
		return err
	}
	// frp 的校验也会拦，但那条报错是英文且埋在配置校验里；这里先给句明白话。
	switch *proxyProtocol {
	case "", "v1", "v2":
	default:
		return fmt.Errorf("-proxy-protocol 只接受 v1 或 v2（收到 %q）", *proxyProtocol)
	}
	if *metaToken != "" {
		ep.Metas = map[string]string{"token": *metaToken}
	}

	ctx, stop := signalContext()
	defer stop()

	fmt.Printf("发布本地端口 %d 为房间 %q（P2P）\n", *localPort, room.Name)
	picked, err := stunpick.Resolve(ep.STUNServer, func(f string, a ...any) {
		fmt.Printf(f+"\n", a...)
	})
	if err != nil {
		return err
	}
	ep.STUNServer = picked
	fmt.Printf("frps %s:%d\n", ep.ServerAddr, ep.ServerPort)
	if *proxyProtocol != "" {
		fmt.Printf("PROXY protocol %s 已启用：确保 MC 服务端装有剥头组件，"+
			"否则玩家会连不上（当前 frp 版本仅 stcp 中转路径实际带头）\n", *proxyProtocol)
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
		return 0, fmt.Errorf("找不到可用端口: %w", err)
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
