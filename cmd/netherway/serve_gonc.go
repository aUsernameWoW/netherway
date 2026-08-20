//go:build !nogonc

package main

import (
	"fmt"
	"os"

	"github.com/aUsernameWoW/netherway/internal/backend/goncp2p"
	"github.com/aUsernameWoW/netherway/internal/i18n"
)

// serveGonc is the gonc-p2p publish path: no frps, no rendezvous, no
// per-player token layer — the MQTT brokers are the rendezvous and the
// session key is the whole admission story (same params the server hands
// out in credentials; Java side composes them in ServeCommand).
func serveGonc(params map[string]string, localPort, rendezvousPort int, proxyProtocol string) error {
	if rendezvousPort != 0 {
		return i18n.Errorf("serve.goncRendezvous")
	}
	if err := checkProxyProtocol(proxyProtocol); err != nil {
		return err
	}
	ctx, stop := signalContext()
	defer stop()
	fmt.Println(i18n.T("serve.goncPublish", localPort))
	if proxyProtocol != "" {
		fmt.Println(i18n.T("serve.goncProxyProtocolOn", proxyProtocol))
	}
	return goncp2p.Serve(ctx, params, localPort,
		goncp2p.ServeOptions{ProxyProtocol: proxyProtocol}, os.Stdout,
		func(f string, a ...any) { fmt.Printf(f+"\n", a...) })
}
