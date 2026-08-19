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
	"strings"
	"time"

	"github.com/aUsernameWoW/netherway/internal/i18n"
	"github.com/xtaci/smux"
)

// serveDialTimeout bounds the loopback dial to the Minecraft port per
// stream. Generous: the port is local, failure means the server is down.
const serveDialTimeout = 10 * time.Second

// Serve runs the wait loop: arm an MQTT wait, punch when a player hellos,
// hand the established session to a goroutine, re-arm. Punching is
// deliberately serialized — concurrent punches on one NAT interfere (the
// same reason the mod serializes warmup punches) — while established
// sessions are served concurrently. Returns when ctx is canceled.
func Serve(ctx context.Context, params map[string]string, mcPort int, logw io.Writer, diagf func(string, ...any)) error {
	if diagf == nil {
		diagf = func(string, ...any) {}
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
		go func() {
			serveSession(ctx, conn, mcPort, logw)
			diagf("%s", i18n.T("serve.goncSessionEnd", info.PeerAddress))
		}()
	}
}

// serveSession serves one established player session: every accepted mux
// stream becomes a fresh loopback connection to the Minecraft port.
func serveSession(ctx context.Context, conn net.Conn, mcPort int, logw io.Writer) {
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
			pipe(st, mc)
		}()
	}
}
