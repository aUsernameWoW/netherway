// Server (wait) side: the counterpart of Run, launched on the Minecraft
// server host by `netherway serve -backend gonc-p2p` (normally embedded via
// the server mod, whose ServeCommand composes the flags from the same cfg
// params that go out in credentials).
package goncp2p

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"time"

	"github.com/aUsernameWoW/netherway/internal/i18n"
	"github.com/pires/go-proxyproto"
	"github.com/threatexpert/gonc/v2/easyp2p"
	"github.com/xtaci/smux"
)

// serveDialTimeout bounds the loopback dial to the Minecraft port per
// stream. Generous: the port is local, failure means the server is down.
const serveDialTimeout = 10 * time.Second

// brokerProbeTimeout bounds one readiness probe (see probeBrokers). It has
// to be a deadline of our own: easyp2p's broker clients run with paho's
// ConnectRetry, so against an unreachable broker set the session
// constructor never fails on its own — it only returns when its context
// ends. Note what 5 s does and does not cap: paho's ConnectTimeout (also
// 5 s) only bounds the CONNACK wait after the TCP dial, while the dial
// itself runs on easyp2p's own 30 s net.Dialer. Against a broker that
// drops packets rather than refusing, each probe therefore leaves one
// paho connect attempt (and its Disconnect waiter) finishing in the
// background for up to 30 s — bounded, a handful in flight at most, not a
// leak. A slow-but-live broker just costs one warning line before the
// next probe reaches it. A variable, not a const, so the package test can
// shrink it.
var brokerProbeTimeout = 5 * time.Second

// brokerRetryDelay paces both the readiness probe and the wait loop after a
// failed cycle. Short: the wait itself is the pacing once brokers are up,
// this only breaks tight error loops.
const brokerRetryDelay = 2 * time.Second

// ServeOptions are serve-side options that concern the loopback hop to the
// Minecraft port rather than the tunnel itself; unlike backend params they
// never travel in credentials, so the client mod needs no counterpart.
type ServeOptions struct {
	// ProxyProtocol ("v1"/"v2", empty = off) prefixes every loopback
	// connection to the MC port with a PROXY protocol header whose source
	// is the punched peer's public address — one session is one player, so
	// the MC server's login logs and bans see the real player IP (which a
	// relayed setup could never show them). The MC side must
	// strip the header; the mod's sniffer does, and it is sniffing-based,
	// so headerless sessions stay safe either way.
	ProxyProtocol string
	// OnReady is called exactly once, when the serve is considered ready:
	// at least one signaling broker has been reached (probeBrokers), so a
	// player's hello can be heard. nil means no callback. The server mod
	// keys its "tunnel ready" telemetry off the line the caller prints
	// here (the [serve-ready] marker, cmd/netherway/serve_gonc.go).
	OnReady func()
	// Warnf receives warning-level diagnostics (retry loops, degraded
	// sessions); nil falls back to the plain diagf argument of Serve. The
	// split exists so the caller can tag warnings for the server mod's log
	// pump without parsing localized text.
	Warnf func(format string, args ...any)
}

// Serve runs the wait loop: arm an MQTT wait, punch when a player hellos,
// hand the established session to a goroutine, re-arm. Punching is
// deliberately serialized — concurrent punches on one NAT interfere (the
// same reason the mod serializes warmup punches) — while established
// sessions are served concurrently. Returns when ctx is canceled.
func Serve(ctx context.Context, params map[string]string, mcPort int, opts ServeOptions, logw io.Writer, diagf func(string, ...any)) error {
	if diagf == nil {
		diagf = func(string, ...any) {}
	}
	warnf := opts.Warnf
	if warnf == nil {
		warnf = diagf
	}
	onReady := opts.OnReady
	if onReady == nil {
		onReady = func() {}
	}
	switch opts.ProxyProtocol {
	case "", "v1", "v2":
	default:
		return i18n.Errorf("serve.badProxyProtocol", opts.ProxyProtocol)
	}
	if unknown := unknownKeys(params); len(unknown) > 0 {
		diagf("%s", i18n.T("goncp2p.unknownKeys",
			unknown, strings.Join(knownKeys(), ", ")))
	}
	cfg, err := parseParams(params)
	if err != nil {
		return err
	}
	diagf("%s", i18n.T("goncp2p.effective",
		ParamSessionKey, presence(cfg.key), ParamNetwork, cfg.network,
		ParamBrokers, listOrDefault(cfg.brokers), ParamSTUN, listOrDefault(cfg.stun)))
	applyServerLists(cfg)

	// Readiness = a signaling broker answers. Probe until one does (or ctx
	// ends), announce once, then arm the wait loop. Without this the wait
	// would sit silently inside easyp2p's connect retries and the server
	// mod could never tell "waiting for players" from "no broker at all".
	for {
		if ctx.Err() != nil {
			return nil
		}
		if err := probeBrokers(ctx, cfg, logw); err == nil {
			break
		} else if ctx.Err() == nil {
			warnf("%s", i18n.T("serve.goncBrokerUnreachable", err))
		}
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(brokerRetryDelay):
		}
	}
	// A broker may have answered in the same instant the context was
	// canceled (probeBrokers returns nil whenever the connect landed
	// before its deadline); never announce readiness on the way out.
	if ctx.Err() != nil {
		return nil
	}
	onReady()

	for {
		if ctx.Err() != nil {
			return nil
		}
		conn, info, err := establish(ctx, cfg, roleWait, logw)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			if errors.Is(err, errWaitIdle) {
				// Routine: a whole waitTimeout passed without a hello.
				// Info, not a warning — an empty server overnight must
				// not fill the log with alarms. No pause either: the
				// wait itself was the pacing.
				diagf("%s", i18n.T("serve.goncWaitIdle"))
				continue
			}
			warnf("%s", i18n.T("serve.goncRetry", err))
			// The wait itself is the pacing (it blocks until a hello or its
			// own timeout); a short pause only breaks tight error loops,
			// e.g. when no broker is reachable.
			select {
			case <-ctx.Done():
				return nil
			case <-time.After(brokerRetryDelay):
			}
			continue
		}
		diagf("%s", i18n.T("serve.goncSession",
			info.PeerAddress, strings.Join(info.NetworksUsed, "+")))
		var hdr []byte
		if opts.ProxyProtocol != "" {
			hdr, err = proxyHeader(opts.ProxyProtocol, info.PeerAddress, mcPort)
			if err != nil {
				warnf("%s", i18n.T("serve.goncProxyHeaderSkip", info.PeerAddress, err))
				hdr = nil
			}
		}
		go func() {
			serveSession(ctx, conn, mcPort, hdr, logw)
			diagf("%s", i18n.T("serve.goncSessionEnd", info.PeerAddress))
		}()
	}
}

// probeBrokers opens (and immediately closes) a signaling session against
// the configured broker list — the same call the wait side makes first,
// see easyp2p.MqttWaitSession — so success means a hello could be heard.
// applyServerLists must have run: NewMQTTSignalSession reads the broker
// list from the easyp2p package globals. The deadline is ours
// (brokerProbeTimeout); the constructor itself only gives up with the
// context, because paho keeps retrying the connect underneath it.
func probeBrokers(ctx context.Context, cfg runConfig, logw io.Writer) error {
	pctx, cancel := context.WithTimeout(ctx, brokerProbeTimeout)
	defer cancel()
	sess, err := easyp2p.NewMQTTSignalSession(pctx,
		easyp2p.MQTT_GenerateClientID(easyp2p.TopicDesc_Signal, cfg.key, 0), "", logw)
	if err != nil {
		return err
	}
	sess.Close()
	return nil
}

// proxyHeader renders the PROXY protocol header for one session; it is
// constant per session (src = the punched peer's public address, dst = the
// loopback the MC dials target), so it is built once and replayed on every
// stream. The dst loopback follows the peer's address family — v1 forbids
// mixing families in one header. The transport is declared TCP even when
// the punched path is UDP: the header describes the byte stream handed to
// the MC port, which is always TCP.
func proxyHeader(version, peer string, mcPort int) ([]byte, error) {
	ap, err := netip.ParseAddrPort(peer)
	if err != nil {
		return nil, err
	}
	addr := ap.Addr().Unmap()
	src := &net.TCPAddr{IP: addr.AsSlice(), Port: int(ap.Port())}
	loop := net.IP{127, 0, 0, 1}
	if addr.Is6() {
		loop = net.IPv6loopback
	}
	v := byte(1)
	if version == "v2" {
		v = 2
	}
	return proxyproto.HeaderProxyFromAddrs(v, src, &net.TCPAddr{IP: loop, Port: mcPort}).Format()
}

// serveSession serves one established player session: every accepted mux
// stream becomes a fresh loopback connection to the Minecraft port. A
// non-empty proxyHdr is written to the MC port ahead of each stream's bytes.
func serveSession(ctx context.Context, conn net.Conn, mcPort int, proxyHdr []byte, logw io.Writer) {
	defer conn.Close()
	sess, err := smux.Server(conn, muxConfig())
	if err != nil {
		fmt.Fprintf(logw, "mux server setup failed: %v\n", err)
		return
	}
	defer sess.Close()

	stop := make(chan struct{})
	defer close(stop)
	go func() {
		select {
		case <-ctx.Done():
			sess.Close()
		case <-stop:
		}
	}()

	for {
		st, err := sess.AcceptStream()
		if err != nil {
			return
		}
		go func() {
			defer st.Close()
			mc, err := net.DialTimeout("tcp",
				net.JoinHostPort("127.0.0.1", fmt.Sprint(mcPort)), serveDialTimeout)
			if err != nil {
				fmt.Fprintf(logw, "dial minecraft port %d failed: %v\n", mcPort, err)
				return
			}
			if len(proxyHdr) > 0 {
				if _, err := mc.Write(proxyHdr); err != nil {
					fmt.Fprintf(logw, "write PROXY header to minecraft port %d failed: %v\n", mcPort, err)
					_ = mc.Close()
					return
				}
			}
			pipe(st, mc)
		}()
	}
}
