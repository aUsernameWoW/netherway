package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/ripplecraft/xtcpinmc/internal/authplugin"
)

// authplugin 子命令在 frps 宿主机运行，作为 frps 的 HTTP server plugin
// 做每玩家令牌校验。frps.toml 对应配置：
//
//	[[httpPlugins]]
//	name = "xtcpinmc-auth"
//	addr = "127.0.0.1:7200"
//	path = "/handler"
//	ops = ["Login", "NewProxy"]
//
// 注意 frps 调不到插件时会拒绝登录（fail-closed），生产环境请交给
// systemd 之类的守护并设置自动重启。

// stringList 收集可重复的旗标。
type stringList []string

func (s *stringList) String() string { return fmt.Sprintf("%d 个", len(*s)) }

func (s *stringList) Set(v string) error {
	*s = append(*s, v)
	return nil
}

func cmdAuthPlugin(args []string) error {
	fs := flag.NewFlagSet("authplugin", flag.ExitOnError)
	listen := fs.String("listen", "127.0.0.1:7200",
		"监听地址；应只对 frps 可达，通常是 127.0.0.1")
	path := fs.String("path", "/handler", "HTTP 路径，与 frps.toml 的 httpPlugins.path 一致")
	key := fs.String("key", os.Getenv("XTCPINMC_AUTH_KEY"),
		"每玩家令牌的签发密钥，须与服务端 mod 的 server.tokenSigningKey 一致；\n"+
			"也可经环境变量 XTCPINMC_AUTH_KEY 传入（避免出现在进程列表里）")
	allowLegacy := fs.Bool("allow-legacy", false,
		"放行不带令牌的登录与代理注册（迁移期用，稳定后应关闭）")
	var statics stringList
	fs.Var(&statics, "static-token",
		"静态令牌，可重复；serve 端用 -meta-token 携带同一值以表明身份并获准注册代理")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *key == "" {
		return errors.New("未指定签发密钥：用 -key 或环境变量 XTCPINMC_AUTH_KEY")
	}

	logf := func(format string, a ...any) {
		fmt.Printf(time.Now().Format("2006-01-02 15:04:05")+" "+format+"\n", a...)
	}
	handler := authplugin.NewHandler(authplugin.Config{
		SigningKey:   *key,
		StaticTokens: statics,
		AllowLegacy:  *allowLegacy,
		Logf:         logf,
	})

	mux := http.NewServeMux()
	mux.Handle(*path, handler)
	srv := &http.Server{Addr: *listen, Handler: mux}

	ctx, stop := signalContext()
	defer stop()

	// 指纹供与服务端 mod 的启动日志核对，两侧一致才说明密钥没配岔
	logf("authplugin 监听 %s%s，签发密钥指纹 %s，静态令牌 %d 个，legacy=%v",
		*listen, *path, authplugin.KeyFingerprint(*key), len(statics), *allowLegacy)

	errCh := make(chan error, 1)
	go func() { errCh <- srv.ListenAndServe() }()
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
		logf("authplugin 已停止")
		return nil
	case err := <-errCh:
		return err
	}
}
