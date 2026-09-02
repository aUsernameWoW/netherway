// Package goncp2p is the gonc-based P2P backend: NAT traversal signaled over
// MQTT brokers (gonc's easyp2p), a TLS 1.3 / DTLS+KCP transport whose mutual
// authentication derives from a shared session key, and smux multiplexing so
// every accepted local TCP connection becomes one stream to the Minecraft
// server.
//
// There is no rendezvous server of its own anywhere in the path: the MQTT
// brokers are the rendezvous, so credentials carry no server address.
// Both tunnel endpoints are this binary — the wire format between them
// (punch sync, secure negotiation, mux framing) only has to agree with
// itself, but it DOES have to agree across mod releases. gonc offers no
// cross-version protocol compatibility promise, so the dependency is pinned
// in go.mod and must be bumped on both sides in lockstep, re-running the
// glue tests in this package.
package goncp2p

import (
	"context"
	"io"
	"os"
	"slices"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/aUsernameWoW/netherway/internal/backend"
	"github.com/aUsernameWoW/netherway/internal/i18n"
	"github.com/threatexpert/gonc/v2/easyp2p"
)

// Name identifies this backend in credentials and on the command line.
const Name = "gonc-p2p"

// Parameter keys. Handed out by the server in credentials (and accepted as
// -O on the command line); must stay byte-identical with the Java side
// Credentials.goncP2p factory.
const (
	// ParamSessionKey is the shared room secret: it derives the MQTT topic,
	// the punch sync encryption, and the TLS/DTLS mutual-auth certificate.
	ParamSessionKey = "sessionKey"
	// ParamBrokers is an optional comma-separated list of MQTT broker URLs
	// (gonc syntax, e.g. "tcp://host:1883"). Empty keeps gonc's defaults
	// (public brokers). An entry may be the BrokerOrigin placeholder.
	ParamBrokers = "brokers"
	// ParamSTUN is an optional comma-separated list of STUN servers in gonc
	// syntax (scheme://host:port or host:port). The mod bridge's NAT
	// telemetry probe reads the same list (ApplyServerLists).
	ParamSTUN = "stunServers"
	// ParamNetwork optionally pins the traversal network. Default "any"
	// races IPv6 TCP > IPv4 TCP > IPv4 UDP like the gonc CLI.
	ParamNetwork = "network"
	// ParamRoom is the display/dedup room name every backend's credentials
	// carry (a Java-side invariant). It has no functional role here —
	// pairing is entirely keyed by the session key — but it must be a known
	// key so credentials pass through without unknown-key warnings.
	ParamRoom = "room"
)

// BrokerOrigin is the placeholder a ParamBrokers entry may carry instead of
// a URL. Under the embedded rendezvous the server rarely knows its own
// public entry, but the client always knows where it connected (CLAUDE.md,
// 凭证的服务入口与会合点地址由客户端补): the server mod hands out credentials
// with brokers=origin, the CLIENT replaces it with tcp://<host>:<port> of the
// Minecraft entry the credential came from (Java Credentials.rendezvousAt,
// so the player's MQTT CONNECT travels through the Minecraft port into the
// sniffer relay), and the SERVER's serve replaces it with its own embedded
// loopback broker (ResolveOriginBroker). Reaching a backend unresolved is
// an error, never a silent drop (parseParams).
//
// Cross-language pin: the Java side mirrors this literal as
// Credentials.BROKER_ORIGIN; TestBrokerOriginLiteral here and the Java
// SelfTest both pin "origin". Change both or neither.
const BrokerOrigin = "origin"

var allowedNetworks = []string{"any", "tcp", "udp", "tcp4", "udp4", "tcp6", "udp6"}

// attemptRetryDelay separates client punch attempts. Each attempt already
// spends tens of seconds inside hello/punch timeouts; this only prevents a
// tight loop on fast failures (e.g. no broker reachable).
const attemptRetryDelay = 5 * time.Second

// helloTimeout bounds one MQTT hello round-trip (client side), matching the
// gonc CLI. The overall punch budget is enforced by the caller's SLP-probe
// deadline, not here.
const helloTimeout = 15 * time.Second

// waitTimeout bounds one MQTT wait cycle (server side) before re-arming,
// matching the gonc CLI. Re-arming is harmless; this only recycles the
// broker subscriptions now and then.
const waitTimeout = 30 * time.Minute

type runConfig struct {
	key     string
	network string
	brokers []string
	stun    []string
}

type impl struct{}

// New returns the gonc P2P backend.
func New() backend.Backend { return impl{} }

func (impl) Name() string { return Name }

// Run implements the client (hello) side: punch, then serve the local bind
// port, one mux stream per accepted connection. It retries the punch until
// the context ends; once a session is established it returns only when the
// session dies (error) or the context is canceled (nil). No relay fallback,
// per the backend contract.
func (impl) Run(ctx context.Context, params map[string]string, opts backend.Options) error {
	if unknown := unknownKeys(params); len(unknown) > 0 {
		opts.Diagf("%s", i18n.T("goncp2p.unknownKeys",
			unknown, strings.Join(knownKeys(), ", ")))
	}
	cfg, err := parseParams(params)
	if err != nil {
		return err
	}
	opts.Diagf("%s", i18n.T("goncp2p.effective",
		ParamSessionKey, presence(cfg.key), ParamNetwork, cfg.network,
		ParamBrokers, listOrDefault(cfg.brokers), ParamSTUN, listOrDefault(cfg.stun)))
	applyServerLists(cfg)

	logw, closeLog := openLog(opts)
	defer closeLog()

	for {
		conn, info, err := establish(ctx, cfg, roleHello, logw)
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			opts.Diagf("%s", i18n.T("goncp2p.retry", err, attemptRetryDelay))
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(attemptRetryDelay):
			}
			continue
		}
		opts.Diagf("%s", i18n.T("goncp2p.established",
			info.PeerAddress, strings.Join(info.NetworksUsed, "+"), opts.BindPort))
		return serveLocal(ctx, conn, opts)
	}
}

func parseParams(params map[string]string) (runConfig, error) {
	cfg := runConfig{
		key:     params[ParamSessionKey],
		network: params[ParamNetwork],
	}
	if cfg.key == "" {
		return cfg, i18n.Errorf("goncp2p.noSessionKey", ParamSessionKey)
	}
	if cfg.network == "" {
		cfg.network = "any"
	}
	ok := false
	for _, n := range allowedNetworks {
		if cfg.network == n {
			ok = true
			break
		}
	}
	if !ok {
		return cfg, i18n.Errorf("goncp2p.badNetwork",
			ParamNetwork, cfg.network, strings.Join(allowedNetworks, ", "))
	}
	cfg.brokers = splitList(params[ParamBrokers])
	// The placeholder must have been substituted upstream (client mod or
	// serve -rendezvous); handing gonc a bare "origin" would make it dial
	// a host literally named origin, failing in a way nobody could read.
	for _, b := range cfg.brokers {
		if b == BrokerOrigin {
			return cfg, i18n.Errorf("goncp2p.originUnresolved", ParamBrokers, BrokerOrigin)
		}
	}
	cfg.stun = splitList(params[ParamSTUN])
	return cfg, nil
}

// HasOriginBroker reports whether ParamBrokers carries the BrokerOrigin
// placeholder (whitespace-trimmed, comma-separated).
func HasOriginBroker(params map[string]string) bool {
	for _, b := range splitList(params[ParamBrokers]) {
		if b == BrokerOrigin {
			return true
		}
	}
	return false
}

// ResolveOriginBroker returns a copy of params with every BrokerOrigin
// entry of ParamBrokers replaced by brokerURL; an empty or absent broker
// list becomes just brokerURL. This is the serve-side substitution (the
// embedded loopback broker is the origin as seen from the server itself);
// the client-side one lives in Java, Credentials.rendezvousAt. Other
// entries keep their order, so an operator can list the embedded broker
// alongside public ones.
func ResolveOriginBroker(params map[string]string, brokerURL string) map[string]string {
	out := make(map[string]string, len(params)+1)
	for k, v := range params {
		out[k] = v
	}
	list := splitList(params[ParamBrokers])
	if len(list) == 0 {
		out[ParamBrokers] = brokerURL
		return out
	}
	for i, b := range list {
		if b == BrokerOrigin {
			list[i] = brokerURL
		}
	}
	out[ParamBrokers] = strings.Join(list, ",")
	return out
}

// ApplyServerLists overrides gonc's built-in broker/STUN candidates from the
// backend params (ParamBrokers / ParamSTUN, empty keeps the defaults). Run
// and Serve do this themselves; it is exported for the mod bridge's NAT
// telemetry probe, which rides on the same easyp2p globals and runs
// concurrently with Run — calling this once up front, from the same params,
// makes Run's own call a no-op (see applyServerLists) so no goroutine writes
// the lists while another reads them.
func ApplyServerLists(params map[string]string) {
	applyServerLists(runConfig{
		brokers: splitList(params[ParamBrokers]),
		stun:    splitList(params[ParamSTUN]),
	})
}

// applyServerLists writes the easyp2p package globals only when the value
// actually changes; an equal list is left alone so a concurrent reader (the
// NAT probe) never races a redundant write. One agent process runs one
// backend, so a single pre-loop assignment is otherwise safe.
func applyServerLists(cfg runConfig) {
	if len(cfg.brokers) > 0 && !slices.Equal(easyp2p.MQTTBrokerServers, cfg.brokers) {
		easyp2p.MQTTBrokerServers = cfg.brokers
	}
	if len(cfg.stun) > 0 && !slices.Equal(easyp2p.STUNServers, cfg.stun) {
		easyp2p.STUNServers = cfg.stun
	}
}

func splitList(s string) []string {
	var out []string
	for _, part := range strings.Split(s, ",") {
		if p := strings.TrimSpace(part); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func knownKeys() []string {
	return []string{ParamSessionKey, ParamBrokers, ParamSTUN, ParamNetwork, ParamRoom}
}

func unknownKeys(params map[string]string) []string {
	known := map[string]bool{}
	for _, k := range knownKeys() {
		known[k] = true
	}
	var out []string
	for k := range params {
		if !known[k] {
			out = append(out, k)
		}
	}
	sort.Strings(out)
	return out
}

// presence describes a sensitive parameter without printing its value; the
// diagnostics end up in player-visible game logs.
func presence(v string) string {
	if v == "" {
		return i18n.T("goncp2p.empty")
	}
	return i18n.T("goncp2p.set", len(v))
}

func listOrDefault(items []string) string {
	if len(items) == 0 {
		return i18n.T("goncp2p.defaultList")
	}
	return strings.Join(items, ",")
}

// openLog resolves where gonc's own diagnostics go. In tunnel mode LogTo is
// a file (stdout carries the JSON contract) and everything is echoed to
// LogEcho so punch progress reaches the game log; gonc has no log levels to
// filter by, and its steady-state output is quiet.
func openLog(opts backend.Options) (io.Writer, func()) {
	var writers []io.Writer
	closer := func() {}
	if opts.LogTo != "" && opts.LogTo != "console" {
		flags := os.O_CREATE | os.O_WRONLY | os.O_APPEND
		// Crude growth cap in place of rotation: start over past 8 MB.
		if fi, err := os.Stat(opts.LogTo); err == nil && fi.Size() > 8<<20 {
			flags |= os.O_TRUNC
		}
		if f, err := os.OpenFile(opts.LogTo, flags, 0o644); err == nil {
			writers = append(writers, f)
			closer = func() { _ = f.Close() }
		} else {
			opts.Diagf("%s", i18n.T("goncp2p.logOpenFailed", opts.LogTo, err))
		}
	} else {
		writers = append(writers, os.Stderr)
	}
	if opts.LogEcho != nil {
		writers = append(writers, opts.LogEcho)
	}
	var w io.Writer
	switch len(writers) {
	case 0:
		w = io.Discard
	case 1:
		w = writers[0]
	default:
		w = io.MultiWriter(writers...)
	}
	return &syncWriter{w: w}, closer
}

// syncWriter serializes writes: the punch machinery, the negotiation logger
// and per-session handlers all log concurrently.
type syncWriter struct {
	mu sync.Mutex
	w  io.Writer
}

func (s *syncWriter) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.w.Write(p)
}
