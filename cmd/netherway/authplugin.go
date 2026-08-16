package main

import (
	"context"
	"flag"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/aUsernameWoW/netherway/internal/authplugin"
	"github.com/aUsernameWoW/netherway/internal/i18n"
)

// authplugin 子命令在 frps 宿主机运行，作为 frps 的 HTTP server plugin
// 做每玩家令牌校验。frps.toml 对应配置：
//
//	[[httpPlugins]]
//	name = "netherway-auth"
//	addr = "127.0.0.1:7200"
//	path = "/handler"
//	ops = ["Login", "NewProxy"]
//
// 注意 frps 调不到插件时会拒绝登录（fail-closed），生产环境请交给
// systemd 之类的守护并设置自动重启。

// stringList 收集可重复的旗标。
type stringList []string

func (s *stringList) String() string { return i18n.T("auth.count", len(*s)) }

func (s *stringList) Set(v string) error {
	*s = append(*s, v)
	return nil
}

func cmdAuthPlugin(args []string) error {
	fs := flag.NewFlagSet("authplugin", flag.ExitOnError)
	listen := fs.String("listen", "127.0.0.1:7200", i18n.T("flag.auth.listen"))
	path := fs.String("path", "/handler", i18n.T("flag.auth.path"))
	key := fs.String("key", os.Getenv("NETHERWAY_AUTH_KEY"), i18n.T("flag.auth.key"))
	allowLegacy := fs.Bool("allow-legacy", false, i18n.T("flag.auth.allowLegacy"))
	var statics stringList
	fs.Var(&statics, "static-token", i18n.T("flag.auth.staticToken"))
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *key == "" {
		return i18n.Errorf("auth.noKey")
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
	logf("%s", i18n.T("auth.listening",
		*listen, *path, authplugin.KeyFingerprint(*key), len(statics), *allowLegacy))

	errCh := make(chan error, 1)
	go func() { errCh <- srv.ListenAndServe() }()
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
		logf("%s", i18n.T("auth.stopped"))
		return nil
	case err := <-errCh:
		return err
	}
}
