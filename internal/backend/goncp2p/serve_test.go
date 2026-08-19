package goncp2p

import (
	"bufio"
	"bytes"
	"context"
	"io"
	"net"
	"testing"
	"time"

	"github.com/pires/go-proxyproto"
	"github.com/xtaci/smux"
)

// TestProxyHeader pins the wire format of the injected header. The v1
// vectors deliberately mirror the Java-side SelfTest vectors for the
// stripping parser (core ProxyProtocol) — the two ends of this contract
// live in different languages, so both pin the same bytes.
func TestProxyHeader(t *testing.T) {
	v1, err := proxyHeader("v1", "198.51.100.7:40000", 25565)
	if err != nil {
		t.Fatalf("v1 ipv4: %v", err)
	}
	if want := "PROXY TCP4 198.51.100.7 127.0.0.1 40000 25565\r\n"; string(v1) != want {
		t.Fatalf("v1 ipv4 = %q, want %q", v1, want)
	}

	// The dst loopback must follow the peer's family: v1 forbids mixing.
	v16, err := proxyHeader("v1", "[2001:db8::7]:40000", 25565)
	if err != nil {
		t.Fatalf("v1 ipv6: %v", err)
	}
	if want := "PROXY TCP6 2001:db8::7 ::1 40000 25565\r\n"; string(v16) != want {
		t.Fatalf("v1 ipv6 = %q, want %q", v16, want)
	}

	// IPv4-mapped peers (possible from a dual-stack punch socket) must come
	// out as plain TCP4, matching what the MC side logs and bans by.
	mapped, err := proxyHeader("v1", "[::ffff:198.51.100.7]:40000", 25565)
	if err != nil {
		t.Fatalf("v1 mapped: %v", err)
	}
	if !bytes.Equal(mapped, v1) {
		t.Fatalf("v1 mapped = %q, want %q", mapped, v1)
	}

	v2, err := proxyHeader("v2", "198.51.100.7:40000", 25565)
	if err != nil {
		t.Fatalf("v2: %v", err)
	}
	h, err := proxyproto.Read(bufio.NewReader(bytes.NewReader(v2)))
	if err != nil {
		t.Fatalf("v2 read-back: %v", err)
	}
	src, dst, ok := h.TCPAddrs()
	if !ok || src.String() != "198.51.100.7:40000" || dst.String() != "127.0.0.1:25565" {
		t.Fatalf("v2 read-back = %v -> %v (ok=%v)", src, dst, ok)
	}

	if _, err := proxyHeader("v1", "not-an-address", 25565); err == nil {
		t.Fatal("unparsable peer address must error, not emit a bogus header")
	}
}

// TestServeSessionInjectsProxyHeader drives serveSession with a header and
// checks the MC-side listener sees it once per stream, ahead of the payload.
func TestServeSessionInjectsProxyHeader(t *testing.T) {
	hdr, err := proxyHeader("v1", "198.51.100.7:40000", 0)
	if err != nil {
		t.Fatal(err)
	}

	mcLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer mcLn.Close()
	got := make(chan string, 2)
	go func() {
		for {
			c, err := mcLn.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				r := bufio.NewReader(c)
				first, err := r.ReadString('\n')
				if err != nil {
					return
				}
				got <- first
				line, err := r.ReadString('\n')
				if err != nil {
					return
				}
				_, _ = io.WriteString(c, "ECHO:"+line)
			}()
		}
	}()
	mcPort := mcLn.Addr().(*net.TCPAddr).Port

	serverEnd, clientEnd := net.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go serveSession(ctx, serverEnd, mcPort, hdr, io.Discard)

	sess, err := smux.Client(clientEnd, muxConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer sess.Close()
	st, err := sess.OpenStream()
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	st.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.WriteString(st, "hello\n"); err != nil {
		t.Fatal(err)
	}
	line, err := bufio.NewReader(st).ReadString('\n')
	if err != nil {
		t.Fatalf("echo read: %v", err)
	}
	if line != "ECHO:hello\n" {
		t.Fatalf("echo = %q", line)
	}
	select {
	case first := <-got:
		if first != string(hdr) {
			t.Fatalf("first line at MC port = %q, want %q", first, hdr)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("MC listener saw no connection")
	}
}
