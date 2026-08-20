//go:build !nofrp

package main

import (
	"flag"
	"fmt"

	"github.com/aUsernameWoW/netherway/internal/backend/frpxtcp"
	"github.com/aUsernameWoW/netherway/internal/config"
	"github.com/aUsernameWoW/netherway/internal/i18n"
	"github.com/aUsernameWoW/netherway/internal/stunpick"
	"github.com/aUsernameWoW/netherway/internal/tunnel"
)

// defaultBackendName keeps frp-xtcp as the default wherever it is compiled
// in; the gonc-only variant switches the default in variant_nofrp.go.
const defaultBackendName = frpXtcpName

// legacySugarParams keeps the old frp flags (-server/-token/…) working as
// sugar for -O params: manual debugging and old callers stay usable. Only
// flags that were explicitly set are merged; unset ones are left for the
// backend to fill from build-time defaults.
func legacySugarParams(fs *flag.FlagSet, backendName string) map[string]string {
	merged := map[string]string{}
	if backendName != frpxtcp.Name {
		return merged
	}
	sugar := map[string]string{
		"server":      frpxtcp.ParamServer,
		"server-port": frpxtcp.ParamServerPort,
		"token":       frpxtcp.ParamToken,
		"stun":        frpxtcp.ParamSTUN,
		"room":        frpxtcp.ParamRoom,
		"secret":      frpxtcp.ParamSecret,
	}
	fs.Visit(func(f *flag.Flag) {
		if key, ok := sugar[f.Name]; ok {
			merged[key] = f.Value.String()
		}
	})
	return merged
}

// toEndpoint converts the variant-neutral flag sink into frp's endpoint.
func toEndpoint(ep *endpointOpts) *tunnel.Endpoint {
	return &tunnel.Endpoint{
		ServerAddr: ep.ServerAddr,
		ServerPort: ep.ServerPort,
		Token:      ep.Token,
		STUNServer: ep.STUNServer,
	}
}

// consoleLog 是 serve 这类前台命令的日志配置。
func consoleLog(verbose bool) tunnel.LogOptions {
	return tunnel.LogOptions{Level: logLevelOf(verbose), To: "console"}
}

func validate(ep *tunnel.Endpoint, room *config.Room) error {
	if err := ep.Validate(); err != nil {
		return err
	}
	return room.Validate()
}

// serveFrp is the frp-xtcp publish path: classic mode registers the xtcp
// proxy on a public frps, -rendezvous embeds the rendezvous point instead
// (see serveEmbedded).
func serveFrp(epOpts *endpointOpts, room *config.Room, localPort, rendezvousPort int,
	signingKey, metaToken, proxyProtocol string, verbose bool) error {

	ep := toEndpoint(epOpts)
	if rendezvousPort != 0 {
		return serveEmbedded(ep, room, localPort, rendezvousPort,
			signingKey, metaToken, proxyProtocol, verbose)
	}
	if err := validate(ep, room); err != nil {
		return err
	}
	if err := checkProxyProtocol(proxyProtocol); err != nil {
		return err
	}
	if metaToken != "" {
		ep.Metas = map[string]string{"token": metaToken}
	}

	ctx, stop := signalContext()
	defer stop()

	fmt.Println(i18n.T("serve.publish", localPort, room.Name))
	picked, err := stunpick.Resolve(ep.STUNServer, func(f string, a ...any) {
		fmt.Printf(f+"\n", a...)
	})
	if err != nil {
		return err
	}
	ep.STUNServer = picked
	fmt.Printf("frps %s:%d\n", ep.ServerAddr, ep.ServerPort)
	if proxyProtocol != "" {
		fmt.Println(i18n.T("serve.proxyProtocolOn", proxyProtocol))
	}
	return tunnel.Serve(ctx, *ep, *room, localPort,
		tunnel.ServeOptions{ProxyProtocol: proxyProtocol}, consoleLog(verbose))
}
