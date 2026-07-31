// xtcpinmc 通过 frp xtcp 打洞，让玩家 P2P 直连 Minecraft 服务器，
// 并把隧道端口伪装成局域网世界，免去手动填地址。
//
//	xtcpinmc serve    在服务器宿主机运行
//	xtcpinmc join     在玩家机器运行（前台）
//	xtcpinmc start    后台启动 join，供启动器 Pre-launch 调用
//	xtcpinmc stop     停止后台实例，供启动器 Post-exit 调用
package main

import (
	"context"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

	"golang.org/x/sync/errgroup"

	"github.com/ripplecraft/xtcpinmc/internal/config"
	"github.com/ripplecraft/xtcpinmc/internal/lanbeacon"
	"github.com/ripplecraft/xtcpinmc/internal/stunpick"
	"github.com/ripplecraft/xtcpinmc/internal/tunnel"
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
	case "join":
		err = cmdJoin(os.Args[2:])
	case "start":
		err = cmdStart(os.Args[2:])
	case "stop":
		err = cmdStop()
	case "tunnel":
		err = cmdTunnel(os.Args[2:])
	case "authplugin":
		err = cmdAuthPlugin(os.Args[2:])
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
	fmt.Fprint(os.Stderr, `xtcpinmc — Minecraft P2P 直连

用法:
  xtcpinmc serve [选项]    在服务器宿主机运行，把本地端口发布为 P2P 代理
  xtcpinmc join  [选项]    在玩家机器运行，建立隧道并广播成局域网世界
  xtcpinmc start [选项]    后台运行 join（启动器 Pre-launch 用）
  xtcpinmc stop            停止后台实例（启动器 Post-exit 用）
  xtcpinmc tunnel [选项]   供 Minecraft mod 调用：纯 P2P，超时即放弃
  xtcpinmc authplugin [选项]  在 frps 宿主机运行：每玩家令牌校验（frps httpPlugins）

公共选项:
  -server  frps 地址        -port    端口
  -token   frps 令牌        -stun    STUN 服务器
  -room    房间名           -secret  房间密钥
  -v       输出调试日志

join 专有:
  -motd    局域网列表中显示的名字
  -no-beacon  只建隧道，不广播（自行连 127.0.0.1:端口）

serve 专有:
  -meta-token  向 frps 的 authplugin 表明身份的静态令牌（authplugin -static-token 同值）

tunnel 专有:
  -backend   隧道方案，默认 frp-xtcp
  -O key=value  传给 backend 的参数，可重复；frp-xtcp 也可直接用上面的公共选项
  -timeout   建链超时秒数，默认 15，超时返回非零码
  -log-file  backend 日志路径（stdout 留给逐行 JSON 状态）

authplugin 专有:
  -listen    监听地址，默认 127.0.0.1:7200    -path  HTTP 路径，默认 /handler
  -key       令牌签发密钥（或环境变量 XTCPINMC_AUTH_KEY）
  -static-token  静态令牌白名单，可重复      -allow-legacy  迁移期放行无令牌登录

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

// consoleLog 是 serve/join 这类前台命令的日志配置。
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
	if err := fs.Parse(args); err != nil {
		return err
	}
	if err := validate(ep, room); err != nil {
		return err
	}
	if *metaToken != "" {
		ep.Metas = map[string]string{"token": *metaToken}
	}

	ctx, stop := signalContext()
	defer stop()

	fmt.Printf("发布本地端口 %d 为房间 %q（P2P + 中转兜底）\n", *localPort, room.Name)
	picked, err := stunpick.Resolve(ep.STUNServer, func(f string, a ...any) {
		fmt.Printf(f+"\n", a...)
	})
	if err != nil {
		return err
	}
	ep.STUNServer = picked
	fmt.Printf("frps %s:%d\n", ep.ServerAddr, ep.ServerPort)
	return tunnel.Serve(ctx, *ep, *room, *localPort, consoleLog(*verbose))
}

func cmdJoin(args []string) error {
	fs := flag.NewFlagSet("join", flag.ExitOnError)
	ep, room, verbose := endpointFlags(fs)
	wantPort := fs.Int("port", 25565, "本地监听端口，被占用时自动改用空闲端口")
	motd := fs.String("motd", config.DefaultMOTD, "局域网列表中显示的名字")
	noBeacon := fs.Bool("no-beacon", false, "只建隧道，不广播")
	d := config.DefaultTimings()
	fallbackTimeout := fs.Float64("fallback-timeout", d.FallbackTimeout.Seconds(),
		"打洞未成先走中转的等待秒数")
	beaconInterval := fs.Float64("beacon-interval", d.BeaconInterval.Seconds(),
		"局域网广播间隔秒数")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if err := validate(ep, room); err != nil {
		return err
	}

	port, err := pickPort(*wantPort)
	if err != nil {
		return err
	}

	// 开广播时必须监听 0.0.0.0：Minecraft 用广播包的源 IP（网卡地址）
	// 去连，绑到 127.0.0.1 会导致列表里出现但连不上。
	bindAddr := "127.0.0.1"
	if !*noBeacon {
		bindAddr = "0.0.0.0"
	}

	ctx, stop := signalContext()
	defer stop()

	if port != *wantPort {
		fmt.Printf("端口 %d 被占用，改用 %d\n", *wantPort, port)
	}
	fmt.Printf("正在连接房间 %q…\n", room.Name)

	secs := func(v float64) time.Duration { return time.Duration(v * float64(time.Second)) }
	timings := config.Timings{
		FallbackTimeout: secs(*fallbackTimeout),
		BeaconInterval:  secs(*beaconInterval),
	}.Normalize()

	g, gctx := errgroup.WithContext(ctx)
	g.Go(func() error {
		return tunnel.Join(gctx, *ep, *room, tunnel.JoinOptions{
			BindAddr: bindAddr,
			BindPort: port,
			Timings:  timings,
		}, consoleLog(*verbose))
	})

	if *noBeacon {
		fmt.Printf("隧道就绪后在 Minecraft 里直连 127.0.0.1:%d\n", port)
	} else {
		b := &lanbeacon.Beacon{
			MOTD:      *motd,
			Port:      port,
			Interval:  timings.BeaconInterval,
			LocalOnly: true, // 不污染真实局域网
			Logf:      func(f string, a ...any) { fmt.Printf(f+"\n", a...) },
		}
		g.Go(func() error { return b.Run(gctx) })
		fmt.Println("打开 Minecraft →「多人游戏」，服务器会出现在「局域网游戏」里")
	}

	return g.Wait()
}

// pickPort 优先使用 want，被占用时让系统分配一个空闲端口。
// 端口号会写进广播包，所以用哪个都不影响玩家体验。
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
