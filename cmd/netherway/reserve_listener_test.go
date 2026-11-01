package main

import (
	"net"
	"testing"
)

// TestReserveListenerNeverSharesPort pins the cross-talk fix: two agents that
// both ask for the same default port (two modpacks started side by side) must
// not both end up with it. The second one has to fall back to another port.
func TestReserveListenerNeverSharesPort(t *testing.T) {
	probe, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	want := probe.Addr().(*net.TCPAddr).Port
	probe.Close()

	first, err := reserveListener(want)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	if got := first.Addr().(*net.TCPAddr).Port; got != want {
		t.Fatalf("first agent got port %d, want the free requested port %d", got, want)
	}

	second, err := reserveListener(want)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	if got := second.Addr().(*net.TCPAddr).Port; got == want {
		t.Fatalf("second agent got port %d too: two tunnels would share one port", got)
	}
}

func TestReserveListenerAutoPort(t *testing.T) {
	l, err := reserveListener(0)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	if l.Addr().(*net.TCPAddr).Port == 0 {
		t.Fatal("auto-assigned port must be resolved")
	}
	if !l.Addr().(*net.TCPAddr).IP.IsLoopback() {
		t.Fatalf("listener must be loopback-only, got %v", l.Addr())
	}
}
