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
