package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"time"

	"github.com/ripplecraft/xtcpinmc/internal/authbridge"
	"github.com/ripplecraft/xtcpinmc/internal/authplugin"
	"github.com/ripplecraft/xtcpinmc/internal/config"
)

// authbridge 子命令在服务端宿主机运行，是预认证服务：让玩家在进服前
// 用 hasJoined 撮合验证 accessToken，据此提前签发 frp 玩家令牌与房间凭证。
//
// accessToken 全程只在「玩家本机的 prefetch 程序 ↔ 皮肤站」之间流转，
// authbridge 碰不到 token——与 MC 原生进服验证同款安全模型。
//
// 房间参数必须与 serve 端实际注册的同源（同 frps/房间名/密钥），否则
// 预拉取的凭证打洞时密钥不匹配。secret=auto 场景下密钥随服务端重启轮换，
// authbridge 下发的旧密钥会自然失效，玩家走一次中转自动更新缓存。
func cmdAuthBridge(args []string) error {
	fs := flag.NewFlagSet("authbridge", flag.ExitOnError)
	listen := fs.String("listen", "127.0.0.1:7201",
		"监听地址；应只对玩家机器可达（公网暴露或经反代）")
	key := fs.String("key", os.Getenv("XTCPINMC_AUTH_KEY"),
		"令牌签发密钥，须与 authplugin -key、服务端 mod tokenSigningKey 一致；\n"+
			"也可经环境变量 XTCPINMC_AUTH_KEY 传入（避免出现在进程列表里）")
	authServer := fs.String("authserver", config.DefaultAuthServer,
		"皮肤站 API root，如 https://skin.example.com/api/yggdrasil")
	// 房间参数：与 serve 同源
	serverAddr := fs.String("server", config.DefaultServerAddr, "frps 地址")
	serverPort := fs.Int("server-port", config.ServerPortDefault(), "frps 端口")
	token := fs.String("token", config.DefaultToken, "frps 全局令牌")
	stun := fs.String("stun", config.DefaultSTUNServer, "STUN 服务器")
	roomName := fs.String("room", config.DefaultRoom, "房间名")
	secret := fs.String("secret", config.DefaultSecretKey, "房间密钥")
	punchTimeout := fs.Int("punch-timeout", 0,
		"服务端建议的打洞超时秒数；0 表示由客户端配置决定")
	tokenTTLDays := fs.Int("token-ttl-days", 30,
		"签发令牌的有效期天数，与服务端 mod 的 tokenTtlDays 同语义")
	ratePerIP := fs.Int("rate-per-ip", 0,
		"每来源 IP 每分钟请求数上限；0 用默认值 30")
	trustProxy := fs.Bool("trust-proxy-header", false,
		"取 X-Forwarded-For 首跳作为来源 IP（做限流与日志）。\n"+
			"仅当 authbridge 部署在反代之后才能开：直接暴露时开着它，\n"+
			"任何人都能伪造头绕过限流")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *key == "" {
		return errors.New("未指定签发密钥：用 -key 或环境变量 XTCPINMC_AUTH_KEY")
	}
	if *authServer == "" {
		return errors.New("未指定皮肤站 API root：用 -authserver")
	}
	if *roomName == "" || *secret == "" {
		return errors.New("房间名与密钥不能为空")
	}

	// 键名与 frp-xtcp backend 参数契约一致（见 internal/backend/frpxtcp）
	roomParams := map[string]string{
		"server":     *serverAddr,
		"serverPort": strconv.Itoa(*serverPort),
		"token":      *token,
		"stun":       *stun,
		"room":       *roomName,
		"secret":     *secret,
	}

	logf := func(format string, a ...any) {
		fmt.Printf(time.Now().Format("2006-01-02 15:04:05")+" "+format+"\n", a...)
	}
	handler := authbridge.NewHandler(authbridge.Config{
		SigningKey:       *key,
		AuthServer:       *authServer,
		RoomParams:       roomParams,
		PunchTimeoutMs:   *punchTimeout * 1000,
		TokenTTL:         time.Duration(*tokenTTLDays) * 24 * time.Hour,
		PerIPPerMinute:   *ratePerIP,
		TrustProxyHeader: *trustProxy,
		Logf:             logf,
	})

	mux := http.NewServeMux()
	mux.Handle("/", handler)
	srv := &http.Server{
		Addr:    *listen,
		Handler: mux,
		// 这是全项目唯一面向玩家侧公网的进程：不设超时的话，慢速连接
		//（Slowloris）能无限占住连接数。写超时要盖过 confirm 的上游
		// hasJoined 外呼（内部 10s 超时），否则响应会被掐在半路。
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}

	ctx, stop := signalContext()
	defer stop()

	// 指纹供与 authplugin / 服务端 mod 的日志核对，两侧一致才说明密钥没配岔
	logf("authbridge 监听 %s，皮肤站 %s，房间 %q，签发密钥指纹 %s",
		*listen, *authServer, *roomName, authplugin.KeyFingerprint(*key))

	errCh := make(chan error, 1)
	go func() { errCh <- srv.ListenAndServe() }()
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
		logf("authbridge 已停止")
		return nil
	case err := <-errCh:
		return err
	}
}
