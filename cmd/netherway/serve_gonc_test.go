//go:build !nogonc

package main

import "testing"

// TestServeMarkers pins the gonc serve status markers: the Java side
// (core ServeTelemetry.GONC_READY_MARKER / GONC_WARN_MARKER) matches on
// these exact literals, and its SelfTest pins the same strings. Change
// both or the server mod never sees the serve become ready.
func TestServeMarkers(t *testing.T) {
	if ServeReadyMarker != "[serve-ready]" {
		t.Errorf("ServeReadyMarker = %q, want %q", ServeReadyMarker, "[serve-ready]")
	}
	if ServeWarnMarker != "[serve-warn]" {
		t.Errorf("ServeWarnMarker = %q, want %q", ServeWarnMarker, "[serve-warn]")
	}
}

// TestEmbeddedBrokerWanted pins when -rendezvous starts the embedded
// signaling broker: only when the brokers list is empty or carries the
// "origin" placeholder. An explicit, placeholder-free list is the operator
// choosing external brokers; starting an idle loopback broker then would
// make [serve-ready] describe a broker no player is told about.
func TestEmbeddedBrokerWanted(t *testing.T) {
	cases := []struct {
		brokers string
		port    int
		want    bool
	}{
		{"", 17322, true},
		{"  ", 17322, true},
		{"origin", 17322, true},
		{"origin, tcp://broker.example.com:1883", 17322, true},
		{"tcp://broker.example.com:1883", 17322, false},
		{"tcp://origin.example.com:1883", 17322, false},
		{"", 0, false},
		{"origin", 0, false},
	}
	for _, c := range cases {
		params := map[string]string{}
		if c.brokers != "" {
			params["brokers"] = c.brokers
		}
		if got := embeddedBrokerWanted(params, c.port); got != c.want {
			t.Errorf("brokers=%q port=%d: got %v, want %v", c.brokers, c.port, got, c.want)
		}
	}
}
