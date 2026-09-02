// Package signalbroker embeds an MQTT broker into the serve process as the
// gonc-p2p signaling rendezvous, listening on loopback only.
//
// Why embed one: gonc's easyp2p signals hole punching through MQTT brokers,
// and out of the box those are public brokers on the internet. That breaks
// the shape this project is built around (see CLAUDE.md, 内嵌会合点): the
// meeting point belongs to the server process, the only exposed port is the
// Minecraft port, no third-party infrastructure is in the path, and the
// credential's secret only means something to that one server process. So
// the gonc-p2p serve embeds a broker here and the player's MQTT CONNECT reaches
// it through the Minecraft port via the mod's sniffer relay (it is told
// apart from Minecraft traffic by its first bytes, see the Java-side
// MqttConnect detector). Public brokers become an explicit opt-in.
//
// Loopback is not an option, it is the design: there is deliberately no
// BindAddr field. CLAUDE.md's rule for the rendezvous — 会合点只能绑回环,
// binding anything else silently opens a second public port and destroys
// the "one mapped port" premise — applies verbatim, and the regression is
// invisible functionally, so the package test checks the bind scope in a
// bind-based way (never by dialing: a transparent proxy on a dev machine
// accepts connections to any address and would make a dial test pass).
//
// Access control is anonymous on purpose, but with an ACL (aclHook): the
// topic name is a hash derived from the session key and every signaling
// payload is encrypted by easyp2p with that same key, so the broker never
// sees anything it could act on — provided a stranger cannot learn the
// topic name. Anyone on the internet reaches this broker (the sniffer
// relay forwards every MQTT CONNECT that arrives on the Minecraft port,
// there is no login), and mochi's WildcardSubAvailable capability is only
// advertised, never enforced, so a "#" subscription would reveal every
// live session topic and with it the ability to inject junk into an
// exchange (which aborts it, and a malformed nonce even panics gonc's
// decrypt path). The ACL therefore denies wildcard filters ("#", "+") and
// the broker's own "$SYS" tree in both directions; easyp2p only ever uses
// exact topics (pinned by TestGoncSignalingInterop, guarded by
// TestStrangerCannotDiscoverTopics). Anonymous access is acceptable only
// because of that: topics are unguessable and unenumerable. The relay
// itself carries opaque bytes either way.
package signalbroker

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"

	mqtt "github.com/mochi-mqtt/server/v2"
	"github.com/mochi-mqtt/server/v2/listeners"
	"github.com/mochi-mqtt/server/v2/packets"

	"github.com/aUsernameWoW/netherway/internal/i18n"
)

// bindHost is the only address the broker ever listens on. Not
// configurable, see the package comment.
const bindHost = "127.0.0.1"

// maxPacketSize caps one MQTT packet. Signaling payloads are a few hundred
// bytes of encrypted candidate lists; 64 KiB leaves ample room while
// bounding what a misbehaving client can make the broker buffer.
const maxPacketSize = 64 << 10

// Options is the whole broker configuration.
type Options struct {
	// BindPort is the loopback port to listen on. The caller (the server
	// mod) picks it and hands the same number to the sniffer relay, so the
	// two must agree.
	BindPort int
	// Logf receives the broker's own warnings and errors — nothing else,
	// so the caller can route it to its warning channel; nil means silent.
	// The broker is quiet at steady state (info-level chatter is dropped).
	Logf func(format string, args ...any)
}

// Validate checks the options. Only the port can be wrong: the bind
// address is fixed to loopback by construction.
func (o Options) Validate() error {
	if o.BindPort <= 0 || o.BindPort > 65535 {
		return i18n.Errorf("sb.badPort", o.BindPort)
	}
	return nil
}

// Service is a running broker.
type Service struct {
	srv     *mqtt.Server
	ln      net.Listener
	port    int
	once    sync.Once
	done    chan struct{}
	closing atomic.Bool
}

// Port returns the loopback port the broker listens on.
func (s *Service) Port() int { return s.port }

// URL returns the broker address in gonc's broker-list syntax
// (tcp://127.0.0.1:<port>) — what the "origin" placeholder resolves to on
// the serve side.
func (s *Service) URL() string { return BrokerURL(s.port) }

// BrokerURL renders the loopback broker address for a port in gonc's
// broker-list syntax.
func BrokerURL(port int) string {
	return "tcp://" + net.JoinHostPort(bindHost, strconv.Itoa(port))
}

// Start binds the loopback listener and starts the broker. On return the
// broker accepts connections; canceling ctx closes it (Close is also fine
// to call directly, and idempotent).
func Start(ctx context.Context, opts Options) (*Service, error) {
	if err := opts.Validate(); err != nil {
		return nil, err
	}
	logf := opts.Logf
	if logf == nil {
		logf = func(string, ...any) {}
	}
	ln, err := net.Listen("tcp", net.JoinHostPort(bindHost, strconv.Itoa(opts.BindPort)))
	if err != nil {
		return nil, i18n.Errorf("sb.listen", opts.BindPort, err)
	}

	caps := mqtt.NewDefaultServerCapabilities()
	caps.MaximumPacketSize = maxPacketSize
	// A signaling client has a handful of messages in flight at most;
	// small queues turn a stuck client into a disconnect, not a memory
	// balloon.
	caps.MaximumClientWritesPending = 64
	caps.ReceiveMaximum = 32
	caps.MaximumInflight = 64
	// easyp2p publishes QoS 1, never retained (verified against gonc
	// v2.6.9 easyp2p/mqtt_signal.go: Publish(topic, qos=1, retained=false)),
	// and subscribes exact topics only; features beyond that are switched
	// off so a stray client cannot park state in the broker.
	caps.RetainAvailable = 0
	caps.SharedSubAvailable = 0
	// Advertised only (mochi does not enforce it); aclHook is what actually
	// refuses wildcard filters.
	caps.WildcardSubAvailable = 0
	caps.MaximumQos = 1
	// paho connects with CleanSession, so nothing should outlive a
	// disconnect anyway; bound it in case a client asks otherwise.
	caps.MaximumSessionExpiryInterval = 60
	caps.MaximumMessageExpiryInterval = 60
	caps.MaximumClients = 1024

	svc := &Service{ln: ln, port: opts.BindPort, done: make(chan struct{})}
	srv := mqtt.New(&mqtt.Options{
		Capabilities: caps,
		Logger:       newLogger(logf, &svc.closing),
	})
	svc.srv = srv
	if err := srv.AddHook(new(aclHook), nil); err != nil {
		_ = ln.Close()
		return nil, i18n.Errorf("sb.start", err)
	}
	if err := srv.AddListener(listeners.NewNet("signal", ln)); err != nil {
		_ = ln.Close()
		return nil, i18n.Errorf("sb.start", err)
	}
	// Serve starts the listener goroutines and the event loop, then
	// returns; it does not block.
	if err := srv.Serve(); err != nil {
		_ = srv.Close()
		_ = ln.Close()
		return nil, i18n.Errorf("sb.start", err)
	}

	go func() {
		select {
		case <-ctx.Done():
			svc.Close()
		case <-svc.done:
		}
	}()
	return svc, nil
}

// aclHook is the broker's whole access policy: every client may connect
// (anonymous, see the package comment), but a subscription or publication
// may only name one exact topic — no "#"/"+" wildcards, which would let a
// stranger enumerate live session topics, and nothing under "$" (mochi's
// $SYS statistics tree). easyp2p needs exactly that: it publishes and
// subscribes single hashed topics.
type aclHook struct {
	mqtt.HookBase
}

func (h *aclHook) ID() string { return "netherway-acl" }

func (h *aclHook) Provides(b byte) bool {
	return b == mqtt.OnConnectAuthenticate || b == mqtt.OnACLCheck
}

func (h *aclHook) OnConnectAuthenticate(*mqtt.Client, packets.Packet) bool { return true }

func (h *aclHook) OnACLCheck(_ *mqtt.Client, topic string, _ bool) bool {
	return topicAllowed(topic)
}

// topicAllowed is the ACL rule, direction-independent: exact, non-system
// topics only.
func topicAllowed(topic string) bool {
	if topic == "" || strings.HasPrefix(topic, "$") {
		return false
	}
	return !strings.ContainsAny(topic, "#+")
}

// Close stops the broker, disconnecting its clients, and releases the
// listener. Safe to call more than once.
func (s *Service) Close() {
	s.once.Do(func() {
		s.closing.Store(true)
		close(s.done)
		// Server.Close closes every listener (and its clients) itself;
		// closing ours again afterwards is a harmless no-op that also
		// covers a listener that never got attached.
		_ = s.srv.Close()
		_ = s.ln.Close()
	})
}

// newLogger builds the slog logger handed to mochi: warnings and errors
// are forwarded to logf as single lines, everything below is dropped so
// the serve console stays quiet while players come and go. Two routine
// conditions are dropped even at warning level: a peer hanging up without
// a DISCONNECT (mochi logs the read EOF per connection; a relay closing
// under a player is not an alarm) and anything logged while the broker
// itself is shutting down (closing == true), which is our own doing.
func newLogger(logf func(string, ...any), closing *atomic.Bool) *slog.Logger {
	text := slog.NewTextHandler(&lineWriter{logf: logf}, &slog.HandlerOptions{
		Level: slog.LevelWarn,
		ReplaceAttr: func(_ []string, a slog.Attr) slog.Attr {
			if a.Key == slog.TimeKey {
				return slog.Attr{}
			}
			return a
		},
	})
	return slog.New(&quietHandler{Handler: text, closing: closing})
}

// quietHandler filters records before the text handler formats them.
type quietHandler struct {
	slog.Handler
	closing *atomic.Bool
}

func (h *quietHandler) Handle(ctx context.Context, r slog.Record) error {
	if h.closing.Load() {
		return nil
	}
	routine := false
	r.Attrs(func(a slog.Attr) bool {
		if err, ok := a.Value.Any().(error); ok &&
			(errors.Is(err, io.EOF) || errors.Is(err, net.ErrClosed)) {
			routine = true
			return false
		}
		return true
	})
	if routine {
		return nil
	}
	return h.Handler.Handle(ctx, r)
}

func (h *quietHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &quietHandler{Handler: h.Handler.WithAttrs(attrs), closing: h.closing}
}

func (h *quietHandler) WithGroup(name string) slog.Handler {
	return &quietHandler{Handler: h.Handler.WithGroup(name), closing: h.closing}
}

// lineWriter forwards each written line to logf. slog writes one record
// per Write call, so no cross-call buffering is needed.
type lineWriter struct {
	logf func(string, ...any)
}

var _ io.Writer = (*lineWriter)(nil)

func (w *lineWriter) Write(p []byte) (int, error) {
	for _, line := range strings.Split(strings.TrimRight(string(p), "\n"), "\n") {
		if line != "" {
			w.logf("%s", i18n.T("sb.log", line))
		}
	}
	return len(p), nil
}
