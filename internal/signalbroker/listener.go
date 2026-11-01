package signalbroker

import (
	"log/slog"
	"net"
	"sync"

	"github.com/mochi-mqtt/server/v2/listeners"
)

// netListener replaces mochi's listeners.Net (v2.7.9), which has two
// shutdown bugs this broker cannot live with:
//
//   - its accept loop and every per-connection goroutine assign one shared
//     err variable, so a Close that makes Accept fail while a client is
//     still being established is a data race (-race flagged it in CI);
//   - it only closes connections that already completed CONNECT. mochi
//     reads the CONNECT packet with no deadline and Server.Close waits for
//     every connection goroutine, so one peer that sends a partial CONNECT
//     and stalls (the sniffer forwards anything that starts like MQTT) would
//     hang the broker's shutdown, and with it the serve process, forever.
//
// So every accepted connection is tracked until its goroutine returns and
// Close closes whatever is still open. Closing a connection mochi already
// closed is a harmless no-op.
type netListener struct {
	listener net.Listener
	id       string
	log      *slog.Logger

	mu     sync.Mutex
	closed bool
	conns  map[net.Conn]struct{}
}

var _ listeners.Listener = (*netListener)(nil)

func newNetListener(id string, ln net.Listener) *netListener {
	return &netListener{id: id, listener: ln, conns: make(map[net.Conn]struct{})}
}

func (l *netListener) ID() string       { return l.id }
func (l *netListener) Address() string  { return l.listener.Addr().String() }
func (l *netListener) Protocol() string { return l.listener.Addr().Network() }

func (l *netListener) Init(log *slog.Logger) error {
	l.log = log
	return nil
}

func (l *netListener) Serve(establish listeners.EstablishFn) {
	for {
		conn, err := l.listener.Accept()
		if err != nil {
			return
		}
		if !l.track(conn) {
			_ = conn.Close()
			return
		}
		go func() {
			defer l.untrack(conn)
			if err := establish(l.id, conn); err != nil {
				l.log.Warn("", "error", err)
			}
		}()
	}
}

// track registers conn, or reports false once the listener is closed.
func (l *netListener) track(conn net.Conn) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.closed {
		return false
	}
	l.conns[conn] = struct{}{}
	return true
}

func (l *netListener) untrack(conn net.Conn) {
	l.mu.Lock()
	delete(l.conns, conn)
	l.mu.Unlock()
}

func (l *netListener) Close(closeClients listeners.CloseFn) {
	l.mu.Lock()
	first := !l.closed
	l.closed = true
	open := make([]net.Conn, 0, len(l.conns))
	for c := range l.conns {
		open = append(open, c)
	}
	l.mu.Unlock()

	if first {
		closeClients(l.id)
	}
	_ = l.listener.Close()
	for _, c := range open {
		_ = c.Close()
	}
}
