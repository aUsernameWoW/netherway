// Server (wait) side: the counterpart of Run, launched on the Minecraft
// server host by `netherway serve -backend gonc-p2p` (normally embedded via
// the server mod, whose ServeCommand composes the flags from the same cfg
// params that go out in credentials).
package goncp2p

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"time"

	"github.com/aUsernameWoW/netherway/internal/i18n"
	"github.com/pires/go-proxyproto"
	"github.com/xtaci/smux"
)

// serveDialTimeout bounds the loopback dial to the Minecraft port per
// stream. Generous: the port is local, failure means the server is down.
const serveDialTimeout = 10 * time.Second

// ServeOptions are serve-side options that concern the loopback hop to the
// Minecraft port rather than the tunnel itself; unlike backend params they
// never travel in credentials, so the client mod needs no counterpart.
type ServeOptions struct {
	// ProxyProtocol ("v1"/"v2", empty = off) prefixes every loopback
	// connection to the MC port with a PROXY protocol header whose source
	// is the punched peer's public address — one session is one player, so
	// the MC server's login logs and bans see the real player IP (something
	// frp xtcp cannot deliver at all, fatedier/frp#2748). The MC side must
	// strip the header; the mod's sniffer does, and it is sniffing-based,
	// so headerless sessions stay safe either way.
	ProxyProtocol string
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

	for {
		if ctx.Err() != nil {
			return nil
		}
		conn, info, err := establish(ctx, cfg, roleWait, logw)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			diagf("%s", i18n.T("serve.goncRetry", err))
			// The wait itself is the pacing (it blocks until a hello or its
			// own timeout); a short pause only breaks tight error loops,
			// e.g. when no broker is reachable.
			select {
			case <-ctx.Done():
				return nil
			case <-time.After(2 * time.Second):
			}
			continue
		}
		diagf("%s", i18n.T("serve.goncSession",
			info.PeerAddress, strings.Join(info.NetworksUsed, "+")))
		var hdr []byte
		if opts.ProxyProtocol != "" {
			hdr, err = proxyHeader(opts.ProxyProtocol, info.PeerAddress, mcPort)
			if err != nil {
				diagf("%s", i18n.T("serve.goncProxyHeaderSkip", info.PeerAddress, err))
				hdr = nil
			}
		}
		go func() {
			serveSession(ctx, conn, mcPort, hdr, logw)
			diagf("%s", i18n.T("serve.goncSessionEnd", info.PeerAddress))
		}()
	}
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
