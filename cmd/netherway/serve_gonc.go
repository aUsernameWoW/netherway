package main

import (
	"fmt"
	"os"
	"strings"

	"github.com/aUsernameWoW/netherway/internal/backend/goncp2p"
	"github.com/aUsernameWoW/netherway/internal/i18n"
	"github.com/aUsernameWoW/netherway/internal/signalbroker"
)

// Status markers prefixed to serve output so the server mod can classify
// lines without parsing localized text; they are the only contract the
// mod has on serve output. Both literals are mirrored on the Java side in
// ServeTelemetry (GONC_READY_MARKER / GONC_WARN_MARKER); TestServeMarkers
// here and the Java SelfTest pin them, change both together.
const (
	// ServeReadyMarker starts the one line that means "a signaling broker
	// answered, players can be heard".
	ServeReadyMarker = "[serve-ready]"
	// ServeWarnMarker starts every warning-level line (retry loops,
	// degraded sessions); the mod logs those at WARN.
	ServeWarnMarker = "[serve-warn]"
)

// serveGonc is the gonc-p2p publish path: no rendezvous server of its own,
// no per-player token layer — the MQTT brokers are the rendezvous and the
// session key is the whole admission story (same params the server hands
// out in credentials; Java side composes them in ServeCommand).
//
// -rendezvous <port> embeds the rendezvous: the signaling broker
// (internal/signalbroker) runs inside this process on loopback, and players
// reach it through the Minecraft port via the mod's sniffer relay. The
// credential's brokers list then carries the
// "origin" placeholder, which the client resolves to the Minecraft entry
// and this side resolves to its own loopback broker. Without -rendezvous
// the brokers are whatever the params say (public ones by default), and an
// unresolved placeholder is refused up front rather than dialed. An
// explicit brokers list without the placeholder is the operator opting out
// of the embedded broker even under -rendezvous: it is then not started
// (starting an idle one would make [serve-ready] describe a broker no
// player is ever told about).
func serveGonc(params map[string]string, localPort, rendezvousPort int, proxyProtocol string) error {
	if err := checkProxyProtocol(proxyProtocol); err != nil {
		return err
	}
	if rendezvousPort == 0 && goncp2p.HasOriginBroker(params) {
		return i18n.Errorf("serve.goncOriginNeedsRendezvous", goncp2p.ParamBrokers, goncp2p.BrokerOrigin)
	}
	ctx, stop := signalContext()
	defer stop()
	say := func(f string, a ...any) { fmt.Printf(f+"\n", a...) }
	warnf := func(f string, a ...any) { fmt.Println(ServeWarnMarker + " " + fmt.Sprintf(f, a...)) }

	if rendezvousPort != 0 && !embeddedBrokerWanted(params, rendezvousPort) {
		say("%s", i18n.T("serve.goncExternalBrokers", rendezvousPort, goncp2p.ParamBrokers,
			goncp2p.BrokerOrigin, goncp2p.BrokerOrigin))
	}
	if embeddedBrokerWanted(params, rendezvousPort) {
		// Start the broker before Serve: Serve's readiness probe dials the
		// broker list, which now points at loopback, so [serve-ready] means
		// the embedded broker is up (contract C3: no new marker needed).
		// The broker's own warnings ride the [serve-warn] channel like
		// every other warning of this process.
		broker, err := signalbroker.Start(ctx, signalbroker.Options{
			BindPort: rendezvousPort,
			Logf:     warnf,
		})
		if err != nil {
			return err
		}
		defer func() {
			broker.Close()
			say("%s", i18n.T("sb.stopped"))
		}()
		params = goncp2p.ResolveOriginBroker(params, broker.URL())
		say("%s", i18n.T("serve.goncEmbeddedBroker", localPort, rendezvousPort))
	} else {
		say("%s", i18n.T("serve.goncPublish", localPort))
	}
	if proxyProtocol != "" {
		say("%s", i18n.T("serve.goncProxyProtocolOn", proxyProtocol))
	}
	return goncp2p.Serve(ctx, params, localPort,
		goncp2p.ServeOptions{
			ProxyProtocol: proxyProtocol,
			OnReady: func() {
				fmt.Println(ServeReadyMarker + " " + i18n.T("serve.goncReady", localPort))
			},
			Warnf: warnf,
		}, os.Stdout, say)
}

// embeddedBrokerWanted decides whether -rendezvous <port> actually starts
// the embedded signaling broker: yes when the flag is set and the brokers
// list is either empty (the broker is then the only one) or names the
// "origin" placeholder (resolved to it). An explicit list without the
// placeholder means external brokers were chosen; the embedded broker
// would be started for nobody, so it is not.
func embeddedBrokerWanted(params map[string]string, rendezvousPort int) bool {
	if rendezvousPort == 0 {
		return false
	}
	return strings.TrimSpace(params[goncp2p.ParamBrokers]) == "" || goncp2p.HasOriginBroker(params)
}
