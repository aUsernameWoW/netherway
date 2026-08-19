package goncp2p

import (
	"net"

	"github.com/xtaci/smux"
)

// newTestMuxServer opens a bare smux server on a test transport, standing in
// for the wait side when a test needs to control the session lifecycle.
func newTestMuxServer(conn net.Conn) (*smux.Session, error) {
	return smux.Server(conn, muxConfig())
}
