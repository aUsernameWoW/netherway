package signalbroker

import (
	"context"
	"fmt"
	"io"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	paho "github.com/eclipse/paho.mqtt.golang"
	"github.com/threatexpert/gonc/v2/easyp2p"

	"github.com/aUsernameWoW/netherway/internal/i18n"
)

// Text assertions use the zh catalog (same convention as the rendezvous
// tests); en is covered by internal/i18n's parity test.
func init() { i18n.Use(i18n.ZH) }

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("no free port: %v", err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

func TestValidateRejectsBadPorts(t *testing.T) {
	for _, port := range []int{0, -1, 65536, 70000} {
		err := (Options{BindPort: port}).Validate()
		if err == nil {
			t.Fatalf("port %d accepted", port)
		}
		if !strings.Contains(err.Error(), "端口非法") {
			t.Fatalf("port %d: error does not name the port: %v", port, err)
		}
	}
	if err := (Options{BindPort: 1883}).Validate(); err != nil {
		t.Fatalf("valid port rejected: %v", err)
	}
}

// TestNoBindAddressField pins the design: the loopback constraint is
// structural, not a validated option. If someone adds a bind address to
// Options this test must be revisited together with the package comment
// and CLAUDE.md's 会合点只能绑回环 rule.
func TestNoBindAddressField(t *testing.T) {
	if !strings.HasPrefix(BrokerURL(1883), "tcp://127.0.0.1:") {
		t.Fatalf("BrokerURL is not loopback: %s", BrokerURL(1883))
	}
	if ip := net.ParseIP(bindHost); ip == nil || !ip.IsLoopback() {
		t.Fatalf("bindHost %q is not a loopback address", bindHost)
	}
}

// TestStartListensOnlyOnLoopback is the important one, mirroring
// internal/rendezvous.TestStartListensOnlyOnBoundPort: the broker must open
// exactly the agreed loopback port and nothing else. Opening a second,
// non-loopback port would silently break the "only one mapped port"
// premise, and that regression is invisible functionally. The check is
// bind-based — after Start, the same port must still be bindable on every
// non-loopback local address — and deliberately NOT dial-based: transparent
// proxies (fake-ip mode) on developer machines accept connections to any
// address:port, so a successful dial proves nothing (the rendezvous test's
// first version false-passed exactly that way). Binding is answered by the
// OS and cannot be fooled.
func TestStartListensOnlyOnLoopback(t *testing.T) {
	port := freePort(t)
	svc, err := Start(t.Context(), Options{BindPort: port})
	if err != nil {
		t.Fatalf("start broker: %v", err)
	}
	defer svc.Close()
	if svc.Port() != port {
		t.Fatalf("Port() = %d, want %d", svc.Port(), port)
	}

	// The agreed port must answer.
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 3*time.Second)
	if err != nil {
		t.Fatalf("broker port unreachable: %v", err)
	}
	conn.Close()

	addrs, err := net.InterfaceAddrs()
	if err != nil {
		t.Skipf("cannot enumerate interface addresses: %v", err)
	}
	checked := 0
	for _, a := range addrs {
		ipnet, ok := a.(*net.IPNet)
		if !ok || ipnet.IP.IsLoopback() || ipnet.IP.To4() == nil {
			continue
		}
		l, err := net.Listen("tcp", fmt.Sprintf("%s:%d", ipnet.IP.String(), port))
		if err != nil {
			t.Fatalf("broker seems to occupy port %d on non-loopback %s (bind scope leaked): %v",
				port, ipnet.IP, err)
		}
		l.Close()
		checked++
	}
	if checked == 0 {
		t.Skip("no non-loopback IPv4 address on this host; bind scope unverifiable")
	}
}

func TestStartRejectsBusyPort(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	port := l.Addr().(*net.TCPAddr).Port
	if _, err := Start(t.Context(), Options{BindPort: port}); err == nil {
		t.Fatal("Start on a busy port must fail")
	}
}

func TestCloseIsIdempotentAndContextCloses(t *testing.T) {
	svc, err := Start(t.Context(), Options{BindPort: freePort(t)})
	if err != nil {
		t.Fatal(err)
	}
	svc.Close()
	svc.Close() // must not panic

	ctx, cancel := context.WithCancel(context.Background())
	port := freePort(t)
	svc, err = Start(ctx, Options{BindPort: port})
	if err != nil {
		t.Fatal(err)
	}
	cancel()
	deadline := time.Now().Add(5 * time.Second)
	for {
		l, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
		if err == nil {
			l.Close()
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("port %d still held after context cancellation", port)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// TestGoncSignalingInterop is the behavior-level guard: gonc's own
// hello/wait signaling (easyp2p) must complete over OUR broker — the
// analogue of internal/rendezvous's frpc-against-embedded-frps interop
// test. Compiling is not enough: a broker capability we switch off, or a
// gonc bump that starts relying on one (retain, shared subscriptions, a
// larger packet, a wildcard), would only show up here. All loopback, no
// network: STUN and punching only begin after the hello, which is where
// this test stops. easyp2p reads its broker list from a package global, so
// it is pointed at the embedded broker for the duration and restored.
func TestGoncSignalingInterop(t *testing.T) {
	svc, err := Start(t.Context(), Options{BindPort: freePort(t)})
	if err != nil {
		t.Fatalf("start broker: %v", err)
	}
	defer svc.Close()
	helloWait(t, svc, "interop-session-key")
}

// helloWait runs one easyp2p wait/hello pair over svc and returns the
// agreed salt (tid); it fails the test if either side errors or they
// disagree. easyp2p reads its broker list from a package global, so it is
// pointed at the embedded broker for the duration and restored.
func helloWait(t *testing.T, svc *Service, key string) string {
	t.Helper()
	saved := easyp2p.MQTTBrokerServers
	easyp2p.MQTTBrokerServers = []string{svc.URL()}
	defer func() { easyp2p.MQTTBrokerServers = saved }()

	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	defer cancel()

	type result struct {
		tid string
		err error
	}
	waitCh := make(chan result, 1)
	go func() {
		tid, sig, err := easyp2p.MqttWaitSession(ctx, key, "", 12*time.Second, io.Discard)
		if sig != nil {
			sig.Close()
		}
		waitCh <- result{tid, err}
	}()
	// Let the wait side subscribe before the hello is pushed; easyp2p
	// re-publishes the hello in bursts anyway, this just keeps the test
	// fast and deterministic.
	time.Sleep(300 * time.Millisecond)

	var hp easyp2p.HelloPayload
	hp.SetControlValue("cs", "tls")
	helloTid, sig, err := easyp2p.MQTTHelloSession(ctx, key, "", hp, 12*time.Second, io.Discard)
	if sig != nil {
		sig.Close()
	}
	if err != nil {
		t.Fatalf("hello over embedded broker failed: %v", err)
	}

	var w result
	select {
	case w = <-waitCh:
	case <-time.After(15 * time.Second):
		t.Fatal("wait side did not return after the hello completed")
	}
	if w.err != nil {
		t.Fatalf("wait over embedded broker failed: %v", w.err)
	}
	if w.tid == "" || w.tid != helloTid {
		t.Fatalf("salt mismatch: wait got %q, hello got %q", w.tid, helloTid)
	}
	return helloTid
}

func TestTopicAllowed(t *testing.T) {
	cases := map[string]bool{
		"nat-exchange/7ac8dbdc281a8988": true,
		"a/b/c":                         true,
		"":                              false,
		"#":                             false,
		"+":                             false,
		"nat-exchange/#":                false,
		"nat-exchange/+":                false,
		"+/x":                           false,
		"$SYS/broker/clients/connected": false,
		"$share/g/nat-exchange/x":       false,
		"$anything":                     false,
	}
	for topic, want := range cases {
		if got := topicAllowed(topic); got != want {
			t.Errorf("topicAllowed(%q) = %v, want %v", topic, got, want)
		}
	}
}

// TestStrangerCannotDiscoverTopics guards the ACL that makes anonymous
// access acceptable: anyone can reach this broker through the sniffer
// relay, and mochi only advertises (never enforces) WildcardSubAvailable,
// so without the hook a "#" subscription would list every live session
// topic and let a stranger inject junk into (or, via gonc's unchecked
// nonce length, crash) an exchange. A stranger connects, is granted
// nothing but exact topics, and sees no message while a real hello/wait
// completes.
func TestStrangerCannotDiscoverTopics(t *testing.T) {
	svc, err := Start(t.Context(), Options{BindPort: freePort(t)})
	if err != nil {
		t.Fatalf("start broker: %v", err)
	}
	defer svc.Close()

	var seen atomic.Int32
	opts := paho.NewClientOptions().AddBroker(svc.URL()).SetClientID("stranger").
		SetConnectTimeout(3 * time.Second).SetConnectRetry(false).SetAutoReconnect(false).
		SetDefaultPublishHandler(func(paho.Client, paho.Message) { seen.Add(1) })
	cl := paho.NewClient(opts)
	if tok := cl.Connect(); !tok.WaitTimeout(5*time.Second) || tok.Error() != nil {
		t.Fatalf("stranger could not connect: %v", tok.Error())
	}
	defer cl.Disconnect(100)

	const refused = 0x80 // SUBACK failure return code (MQTT 3.1.1)
	subscribe := func(filter string) byte {
		t.Helper()
		tok := cl.Subscribe(filter, 1, nil)
		if !tok.WaitTimeout(5 * time.Second) {
			t.Fatalf("subscribe %q: no SUBACK", filter)
		}
		return tok.(*paho.SubscribeToken).Result()[filter]
	}
	for _, filter := range []string{"#", "+", "nat-exchange/#", "nat-exchange/+", "$SYS/#", "$SYS/broker/version"} {
		if code := subscribe(filter); code != refused {
			t.Fatalf("subscribe %q granted with code %#x; wildcard/system filters must be refused", filter, code)
		}
	}
	if code := subscribe("nat-exchange/not-a-live-session"); code != 1 {
		t.Fatalf("exact topic refused with code %#x; easyp2p needs exact QoS 1 subscriptions", code)
	}

	helloWait(t, svc, "guarded-session-key")
	if n := seen.Load(); n != 0 {
		t.Fatalf("stranger observed %d message(s) of a session it cannot name", n)
	}
	if !cl.IsConnectionOpen() {
		t.Fatal("stranger was disconnected; refusal must be a SUBACK code, not a drop (easyp2p shares the client)")
	}
}
