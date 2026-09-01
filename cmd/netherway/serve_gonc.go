//go:build !nogonc

package main

import (
	"fmt"
	"os"

	"github.com/aUsernameWoW/netherway/internal/backend/goncp2p"
	"github.com/aUsernameWoW/netherway/internal/i18n"
)

// Status markers prefixed to gonc serve output so the server mod can
// classify lines without parsing localized text (frp's output carries
// "start proxy success" and " [W] " / " [E] " for the same purpose). Both
// literals are mirrored on the Java side in ServeTelemetry
// (GONC_READY_MARKER / GONC_WARN_MARKER); TestServeMarkers here and the
// Java SelfTest pin them, change both together.
const (
	// ServeReadyMarker starts the one line that means "a signaling broker
	// answered, players can be heard" — the gonc analogue of frp's
	// "start proxy success".
	ServeReadyMarker = "[serve-ready]"
	// ServeWarnMarker starts every warning-level line (retry loops,
	// degraded sessions); the mod logs those at WARN.
	ServeWarnMarker = "[serve-warn]"
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
		goncp2p.ServeOptions{
			ProxyProtocol: proxyProtocol,
			OnReady: func() {
				fmt.Println(ServeReadyMarker + " " + i18n.T("serve.goncReady", localPort))
			},
			Warnf: func(f string, a ...any) {
				fmt.Println(ServeWarnMarker + " " + fmt.Sprintf(f, a...))
			},
		}, os.Stdout,
		func(f string, a ...any) { fmt.Printf(f+"\n", a...) })
}
