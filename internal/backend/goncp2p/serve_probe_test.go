package goncp2p

import (
	"context"
	"io"
	"sync/atomic"
	"testing"
	"time"

	"github.com/threatexpert/gonc/v2/easyp2p"
)

// TestServeWarnsWhileNoBrokerReachable runs Serve against a broker list
// with nothing listening and checks the readiness contract: warnings flow
// through Warnf, OnReady never fires, and cancellation ends Serve cleanly.
// brokerProbeTimeout is shrunk because easyp2p's broker clients retry the
// connect forever underneath the probe (paho ConnectRetry), so the probe
// only fails when its own deadline expires. Serve installs the credential's
// broker/STUN lists into easyp2p's package globals (applyServerLists), so
// those are restored too — otherwise a later test in this binary that
// touches signaling would silently run against the dead broker.
func TestServeWarnsWhileNoBrokerReachable(t *testing.T) {
	savedTimeout := brokerProbeTimeout
	savedBrokers, savedStun := easyp2p.MQTTBrokerServers, easyp2p.STUNServers
	brokerProbeTimeout = 500 * time.Millisecond
	defer func() {
		brokerProbeTimeout = savedTimeout
		easyp2p.MQTTBrokerServers, easyp2p.STUNServers = savedBrokers, savedStun
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()

	var warns, readies int32
	done := make(chan error, 1)
	go func() {
		done <- Serve(ctx, map[string]string{
			ParamSessionKey: "probe-test",
			ParamRoom:       "probe-test",
			ParamBrokers:    "tcp://127.0.0.1:1",
		}, 0, ServeOptions{
			OnReady: func() { atomic.AddInt32(&readies, 1) },
			Warnf:   func(string, ...any) { atomic.AddInt32(&warns, 1) },
		}, io.Discard, nil)
	}()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Serve returned error on cancellation: %v", err)
		}
	case <-time.After(8 * time.Second):
		t.Fatal("Serve did not return after context cancellation")
	}
	if atomic.LoadInt32(&warns) == 0 {
		t.Fatal("expected at least one Warnf call while no broker is reachable")
	}
	if atomic.LoadInt32(&readies) != 0 {
		t.Fatalf("OnReady fired %d times with no broker reachable", readies)
	}
}
