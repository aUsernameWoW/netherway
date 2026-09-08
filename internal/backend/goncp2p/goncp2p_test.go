package goncp2p

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aUsernameWoW/netherway/internal/backend"
	"github.com/aUsernameWoW/netherway/internal/i18n"
)

func init() { i18n.Use(i18n.ZH) }

func TestParseParams(t *testing.T) {
	if _, err := parseParams(map[string]string{}); err == nil {
		t.Fatal("missing sessionKey must be rejected")
	}
	cfg, err := parseParams(map[string]string{ParamSessionKey: "k"})
	if err != nil {
		t.Fatalf("minimal params rejected: %v", err)
	}
	if cfg.network != "any" {
		t.Fatalf("default network = %q, want any", cfg.network)
	}
	if cfg.brokers != nil || cfg.stun != nil {
		t.Fatalf("empty lists must stay nil (keep gonc defaults), got %v / %v", cfg.brokers, cfg.stun)
	}

	cfg, err = parseParams(map[string]string{
		ParamSessionKey: "k",
		ParamNetwork:    "udp4",
		ParamBrokers:    " tcp://a:1883 , tcp://b:1883,",
		ParamSTUN:       "udp://s:3478",
	})
	if err != nil {
		t.Fatalf("full params rejected: %v", err)
	}
	if len(cfg.brokers) != 2 || cfg.brokers[0] != "tcp://a:1883" || cfg.brokers[1] != "tcp://b:1883" {
		t.Fatalf("broker list parsed wrong: %v", cfg.brokers)
	}
	if len(cfg.stun) != 1 || cfg.stun[0] != "udp://s:3478" {
		t.Fatalf("stun list parsed wrong: %v", cfg.stun)
	}

	if _, err := parseParams(map[string]string{ParamSessionKey: "k", ParamNetwork: "carrier-pigeon"}); err == nil {
		t.Fatal("bad network must be rejected")
	}
}

// TestBrokerOriginLiteral pins the cross-language placeholder: the Java
// side mirrors it as Credentials.BROKER_ORIGIN and injects it into
// credentials under the embedded rendezvous; Credentials.rendezvousAt
// (client) and ResolveOriginBroker (serve) substitute it. Change both
// sides or neither.
func TestBrokerOriginLiteral(t *testing.T) {
	if BrokerOrigin != "origin" {
		t.Fatalf("BrokerOrigin = %q, want %q (Java Credentials.BROKER_ORIGIN)", BrokerOrigin, "origin")
	}
}

// TestParseParamsRejectsUnresolvedOrigin: the placeholder must never reach
// gonc — a bare "origin" would be dialed as a host name and fail
// unreadably. Both the tunnel path (Run) and Serve go through parseParams.
func TestParseParamsRejectsUnresolvedOrigin(t *testing.T) {
	for _, brokers := range []string{"origin", " origin ", "tcp://a:1883,origin", "origin,tcp://a:1883"} {
		_, err := parseParams(map[string]string{ParamSessionKey: "k", ParamBrokers: brokers})
		if err == nil {
			t.Fatalf("brokers=%q accepted with an unresolved placeholder", brokers)
		}
		if !strings.Contains(err.Error(), "占位符") || !strings.Contains(err.Error(), `"origin"`) {
			t.Fatalf("brokers=%q: error does not explain the placeholder: %v", brokers, err)
		}
	}
	// Not a substring match: a broker host that merely contains the word
	// is a legitimate URL.
	if _, err := parseParams(map[string]string{ParamSessionKey: "k", ParamBrokers: "tcp://origin.example.com:1883"}); err != nil {
		t.Fatalf("legitimate URL rejected: %v", err)
	}
}

func TestHasOriginBroker(t *testing.T) {
	cases := map[string]bool{
		"":                              false,
		"tcp://a:1883":                  false,
		"tcp://origin.example.com:1883": false,
		"origin":                        true,
		" origin , tcp://a:1883":        true,
		"tcp://a:1883,origin":           true,
	}
	for brokers, want := range cases {
		if got := HasOriginBroker(map[string]string{ParamBrokers: brokers}); got != want {
			t.Errorf("HasOriginBroker(%q) = %v, want %v", brokers, got, want)
		}
	}
	if HasOriginBroker(map[string]string{}) {
		t.Error("absent brokers reported as containing origin")
	}
}

func TestResolveOriginBroker(t *testing.T) {
	const url = "tcp://127.0.0.1:17322"
	cases := map[string]string{
		"":                             url,
		"origin":                       url,
		"origin,tcp://b:1883":          url + ",tcp://b:1883",
		"tcp://a:1883, origin ,origin": "tcp://a:1883," + url + "," + url,
		"tcp://a:1883":                 "tcp://a:1883",
	}
	for brokers, want := range cases {
		in := map[string]string{ParamSessionKey: "k", ParamBrokers: brokers}
		out := ResolveOriginBroker(in, url)
		if out[ParamBrokers] != want {
			t.Errorf("ResolveOriginBroker(%q) = %q, want %q", brokers, out[ParamBrokers], want)
		}
		if out[ParamSessionKey] != "k" {
			t.Errorf("other keys not copied: %v", out)
		}
		if in[ParamBrokers] != brokers {
			t.Errorf("input mutated: %q -> %q", brokers, in[ParamBrokers])
		}
	}
	// Absent key: the embedded broker becomes the list.
	out := ResolveOriginBroker(map[string]string{ParamSessionKey: "k"}, url)
	if out[ParamBrokers] != url {
		t.Errorf("absent brokers -> %q, want %q", out[ParamBrokers], url)
	}
	// The resolved map must pass parseParams (the whole point).
	if _, err := parseParams(ResolveOriginBroker(map[string]string{ParamSessionKey: "k", ParamBrokers: "origin"}, url)); err != nil {
		t.Errorf("resolved params rejected: %v", err)
	}
}

func TestUnknownKeys(t *testing.T) {
	got := unknownKeys(map[string]string{
		ParamSessionKey: "k", "server": "x", "aaa": "y",
	})
	if len(got) != 2 || got[0] != "aaa" || got[1] != "server" {
		t.Fatalf("unknownKeys = %v, want [aaa server]", got)
	}
}

// TestMuxGlue pins the whole stream plumbing between our two ends — smux
// client over serveLocal against smux server in serveSession, joined by a
// net.Pipe standing in for the punched+negotiated transport. This is the
// behavior-level guard to re-run whenever the pinned gonc/smux dependencies
// are bumped: if it passes, a new agent still interoperates with an old one
// at the layer we own.
func TestMuxGlue(t *testing.T) {
	// Line-echo server standing in for the Minecraft port.
	echoLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echoLn.Close()
	go func() {
		for {
			c, err := echoLn.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				r := bufio.NewReader(c)
				for {
					line, err := r.ReadString('\n')
					if err != nil {
						return
					}
					if _, err := io.WriteString(c, "ECHO:"+line); err != nil {
						return
					}
				}
			}()
		}
	}()
	mcPort := echoLn.Addr().(*net.TCPAddr).Port

	serverEnd, clientEnd := net.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go serveSession(ctx, serverEnd, mcPort, nil, io.Discard)

	bindLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	bindPort := bindLn.Addr().(*net.TCPAddr).Port
	bindLn.Close() // free it for serveLocal

	localDone := make(chan error, 1)
	go func() {
		localDone <- serveLocal(ctx, clientEnd, backend.Options{
			BindAddr: "127.0.0.1", BindPort: bindPort,
		})
	}()

	roundtrip := func(tag string) error {
		var conn net.Conn
		var err error
		deadline := time.Now().Add(5 * time.Second)
		for {
			conn, err = net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", bindPort), time.Second)
			if err == nil {
				break
			}
			if time.Now().After(deadline) {
				return fmt.Errorf("dial: %w", err)
			}
			time.Sleep(50 * time.Millisecond)
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(5 * time.Second))
		if _, err := io.WriteString(conn, tag+"\n"); err != nil {
			return fmt.Errorf("write: %w", err)
		}
		line, err := bufio.NewReader(conn).ReadString('\n')
		if err != nil {
			return fmt.Errorf("read: %w", err)
		}
		if want := "ECHO:" + tag + "\n"; line != want {
			return fmt.Errorf("got %q, want %q", line, want)
		}
		return nil
	}

	if err := roundtrip("single"); err != nil {
		t.Fatalf("single connection: %v", err)
	}

	// Concurrent connections must multiplex over the one session.
	var wg sync.WaitGroup
	errs := make(chan error, 3)
	for i := range 3 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := roundtrip(fmt.Sprintf("conc-%d", i)); err != nil {
				errs <- fmt.Errorf("conc-%d: %w", i, err)
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}

	// Context cancellation is the clean-stop path: serveLocal returns nil.
	cancel()
	select {
	case err := <-localDone:
		if err != nil {
			t.Fatalf("serveLocal after cancel = %v, want nil", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("serveLocal did not return after cancel")
	}
}

// TestSessionLoss pins the failure contract: when the transport under the
// mux dies, serveLocal must return a non-nil error so the agent process
// exits and the mod rebuilds the tunnel.
func TestSessionLoss(t *testing.T) {
	serverEnd, clientEnd := net.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Keep the server end alive as a raw smux peer, then kill it.
	go func() {
		sess, err := newTestMuxServer(serverEnd)
		if err != nil {
			return
		}
		time.Sleep(300 * time.Millisecond)
		sess.Close()
		serverEnd.Close()
	}()

	bindLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	bindPort := bindLn.Addr().(*net.TCPAddr).Port
	bindLn.Close()

	err = serveLocal(ctx, clientEnd, backend.Options{BindAddr: "127.0.0.1", BindPort: bindPort})
	if err == nil {
		t.Fatal("serveLocal must report an error when the session dies")
	}
	if !strings.Contains(err.Error(), "会话") && !strings.Contains(err.Error(), "session") {
		t.Fatalf("unexpected error text: %v", err)
	}
}
