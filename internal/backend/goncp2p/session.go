// Session establishment and stream plumbing shared by the client (hello)
// and server (wait) sides.
//
// The call sequence mirrors the gonc CLI's cs=tls path exactly — MQTT
// hello/wait for the per-session topic salt, easyp2p punch on
// sessionKey+salt, then secure negotiation (TLS 1.3 over TCP, DTLS+KCP over
// UDP; PSK-derived certificate, mutual auth) — so its field-proven behavior
// carries over unchanged. Only the layer above differs: instead of gonc's
// link protocol we run bare smux, one stream per Minecraft connection, with
// zero framing of our own.
package goncp2p

import (
	"context"
	"crypto/tls"
	"io"
	"log"
	"net"
	"strconv"
	"strings"

	"github.com/aUsernameWoW/netherway/internal/backend"
	"github.com/aUsernameWoW/netherway/internal/i18n"
	"github.com/threatexpert/gonc/v2/easyp2p"
	"github.com/threatexpert/gonc/v2/secure"
	"github.com/xtaci/smux"
)

type role int

const (
	roleHello role = iota // client: initiates, then opens mux streams
	roleWait              // server: waits for hellos, then accepts mux streams
)

// establish performs one signaling + punch + negotiation cycle and returns
// the secured connection. The smux role is fixed by our application role,
// NOT by the punch role: easyp2p elects punch client/server arbitrarily,
// while streams must always be opened by the player side.
func establish(ctx context.Context, cfg runConfig, r role, logw io.Writer) (net.Conn, *easyp2p.P2PConnInfo, error) {
	var salt string
	var signal *easyp2p.MQTTSignalSession
	var err error
	if r == roleHello {
		var hp easyp2p.HelloPayload
		hp.SetControlValue("cs", "tls")
		salt, signal, err = easyp2p.MQTTHelloSession(ctx, cfg.key, "", hp, helloTimeout, logw)
	} else {
		salt, signal, err = easyp2p.MqttWaitSession(ctx, cfg.key, "", waitTimeout, logw)
	}
	if err != nil {
		return nil, nil, err
	}
	defer signal.Close()

	info, err := easyp2p.Easy_P2P_MPWithOptions(ctx, cfg.network, cfg.key+salt,
		easyp2p.EasyP2PMPOptions{
			LogWriter: logw,
			Signal:    signal,
			// RelayConn stays nil on purpose: the backend contract forbids
			// relay fallbacks — a fallback would make the readiness probe
			// succeed without a punched path.
		})
	if err != nil {
		return nil, nil, err
	}
	raw := info.Conns[0]
	nconn, err := negotiate(ctx, cfg.key, info, logw)
	if err != nil {
		_ = raw.Close()
		return nil, nil, err
	}
	return nconn, info, nil
}

// negotiate mirrors gonc's p2pSecureNegotiation for the cs=tls suite. The
// PSK-derived ECDSA certificate plus VerifyPeerCertificateByPSK (wired
// inside DoNegotiationContext when KeyType is PSK) gives mutual
// authentication; InsecureSkipVerify only disables the CA chain check that
// the PSK check replaces.
func negotiate(ctx context.Context, key string, info *easyp2p.P2PConnInfo, logw io.Writer) (net.Conn, error) {
	cfg := secure.NewNegotiationConfig()
	cfg.IsClient = info.IsClient
	cfg.InsecureSkipVerify = true
	cfg.KeepAlive = 30
	cert, err := secure.GenerateECDSACertificate("", key)
	if err != nil {
		return nil, err
	}
	cfg.Certs = []tls.Certificate{*cert}
	cfg.Key = key
	cfg.KeyType = "PSK"
	if strings.HasPrefix(info.Conns[0].LocalAddr().Network(), "udp") {
		cfg.KcpWithUDP = true
		cfg.SecureLayer = "dtls"
	} else {
		cfg.SecureLayer = "tls13"
	}
	return secure.DoNegotiationContext(ctx, cfg, info.Conns[0], log.New(logw, "", log.LstdFlags))
}

// muxConfig keeps smux's own defaults (10 s keepalive interval, 30 s
// timeout) — deliberately tighter than the gonc CLI's 15/120: the interval
// doubles as the NAT-mapping refresh for idle warmup tunnels, and the
// timeout bounds how long a silently-dead transport (UDP blackhole) goes
// unnoticed. Hard transport errors are noticed instantly via AcceptStream,
// see serveLocal.
func muxConfig() *smux.Config {
	return smux.DefaultConfig()
}

// serveLocal runs the client side of an established session: accept local
// TCP connections on the bind address, one smux stream each. Returns nil on
// context cancellation, an error when the session dies — the caller (agent
// process) then exits and the mod rebuilds the tunnel through its normal
// retry path.
func serveLocal(ctx context.Context, conn net.Conn, opts backend.Options) error {
	sess, err := smux.Client(conn, muxConfig())
	if err != nil {
		_ = conn.Close()
		return err
	}
	defer sess.Close()

	ln, err := net.Listen("tcp", net.JoinHostPort(opts.BindAddr, strconv.Itoa(opts.BindPort)))
	if err != nil {
		return err
	}
	defer ln.Close()

	// Transport death must unblock Accept immediately, and smux's IsClosed /
	// CloseChan only flip on explicit Close or keepalive timeout — a socket
	// read error alone does not close the session. AcceptStream, however,
	// unblocks on that error instantly, and the wait side never opens
	// streams, so a pending AcceptStream is a free death detector.
	sessionDead := make(chan struct{})
	go func() {
		_, _ = sess.AcceptStream()
		close(sessionDead)
		ln.Close()
	}()
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		select {
		case <-ctx.Done():
			ln.Close()
		case <-stop:
		}
	}()

	for {
		c, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			select {
			case <-sessionDead:
				return i18n.Errorf("goncp2p.sessionLost")
			default:
				return err
			}
		}
		go func() {
			defer c.Close()
			st, err := sess.OpenStream()
			if err != nil {
				return
			}
			pipe(c, st)
		}()
	}
}

// pipe copies both directions and closes both ends as soon as either
// direction finishes. smux streams have no half-close, so this is the same
// teardown a Minecraft connection gets from any TCP proxy in the chain.
func pipe(a, b net.Conn) {
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(a, b)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(b, a)
		done <- struct{}{}
	}()
	<-done
	_ = a.Close()
	_ = b.Close()
	<-done
}
