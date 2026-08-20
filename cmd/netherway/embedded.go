//go:build !nofrp

package main

import (
	"fmt"

	"github.com/aUsernameWoW/netherway/internal/config"
	"github.com/aUsernameWoW/netherway/internal/i18n"
	"github.com/aUsernameWoW/netherway/internal/rendezvous"
	"github.com/aUsernameWoW/netherway/internal/stunpick"
	"github.com/aUsernameWoW/netherway/internal/tunnel"
)

// serveEmbedded 是 serve 的内嵌会合点模式。
//
// 与经典模式（连公网 frps）的差别只有一处：会合点起在本机回环上，
// 由 MC 服务端把玩家的控制连接从 Minecraft 端口转发进来。打洞本身
// 一字未改——frp 在 xtcp 里只负责转发信令，地址发现靠 STUN，
// 数据流打通后直连，会合点在哪都不影响成功率。
//
// 换来的是公网侧对 netherway 的要求归零：那台机器只要能把 TCP 转到
// Minecraft 端口即可，不必装插件、不必支持 xtcp、不必与本项目同版本，
// 因此可以是租来的隧道服务、nginx stream，甚至一条 NAT 规则。
//
// 令牌与房间密钥都由调用方（服务端 mod）给定并随凭证下发，两者同源；
// 未给定则每次启动随机生成，「重启即轮换」。
func serveEmbedded(ep *tunnel.Endpoint, room *config.Room, localPort, rendezvousPort int,
	signingKey, staticToken, proxyProtocol string, verbose bool) error {

	if err := checkProxyProtocol(proxyProtocol); err != nil {
		return err
	}
	// 房间密钥仍是必需的：它决定谁能对这个房间发起打洞。
	// 而 frps 地址/令牌在这个模式下不再需要——前者是回环，后者本机生成。
	if room.Name == "" {
		return i18n.Errorf("config.emptyRoom")
	}
	if room.SecretKey == "" {
		return i18n.Errorf("config.emptySecret")
	}

	ctx, stop := signalContext()
	defer stop()

	logOpts := consoleLog(verbose)
	// frp 的日志器是进程级全局的，会合点和 frpc 共用一个。
	// 必须赶在起会合点之前初始化，否则它的日志会走 frp 自己的默认去向。
	tunnel.InitLogger(logOpts)

	say := func(f string, a ...any) { fmt.Printf(f+"\n", a...) }

	// 没有签发密钥就没有每玩家令牌可校验，此时绝不能起鉴权端点：插件会
	// 因为玩家凭证里没有 user/userToken 而拒绝所有登录（AllowLegacy 为假），
	// 表现是全员打洞失败而服主完全想不到是这个开关造成的。
	// 静态令牌单独存在没有意义——它的用途是向共享的公网 frps 表明身份，
	// 而内嵌会合点本来就只服务这一个进程。
	if signingKey == "" && staticToken != "" {
		say("%s", i18n.T("serve.staticTokenIgnored"))
		staticToken = ""
	}

	// 开了每玩家令牌校验，serve 自己也得过这一关：它没带令牌的话
	// 会被自己的插件拒登，代理都注册不上。调用方没指定就本机生成一个——
	// 这个令牌纯属进程内自证身份，不进凭证、不需要任何人知道。
	if signingKey != "" && staticToken == "" {
		generated, err := rendezvous.NewToken()
		if err != nil {
			return err
		}
		staticToken = generated
		say("%s", i18n.T("serve.selfTokenGenerated"))
	}

	rz, err := rendezvous.Start(ctx, rendezvous.Options{
		BindPort:     rendezvousPort,
		Token:        ep.Token,
		SigningKey:   signingKey,
		StaticTokens: staticTokensOf(staticToken),
		Logf:         say,
	})
	if err != nil {
		return err
	}
	defer rz.Close()

	// 自己连自己的会合点。静态令牌走 metas，让内嵌 authplugin 认出
	// 「这是 serve」并放行代理注册——玩家侧只有 visitor，注册代理的一律拒绝。
	ep.ServerAddr = "127.0.0.1"
	ep.ServerPort = rendezvousPort
	ep.Token = rz.Token()
	if staticToken != "" {
		ep.Metas = map[string]string{"token": staticToken}
	}

	say("%s", i18n.T("serve.publishEmbedded", localPort, room.Name, rendezvousPort))
	picked, err := stunpick.Resolve(ep.STUNServer, say)
	if err != nil {
		return err
	}
	ep.STUNServer = picked
	if proxyProtocol != "" {
		say("%s", i18n.T("serve.proxyProtocolOn", proxyProtocol))
	}

	return tunnel.Serve(ctx, *ep, *room, localPort,
		tunnel.ServeOptions{ProxyProtocol: proxyProtocol}, logOpts)
}

// staticTokensOf 把单个静态令牌转成切片；空串表示不设。
func staticTokensOf(token string) []string {
	if token == "" {
		return nil
	}
	return []string{token}
}
