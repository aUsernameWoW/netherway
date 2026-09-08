package main

import (
	"context"
	"io"
	"time"

	"github.com/threatexpert/gonc/v2/easyp2p"

	"github.com/aUsernameWoW/netherway/internal/backend/goncp2p"
	"github.com/aUsernameWoW/netherway/internal/i18n"
)

// Wire values of the NAT shape in tunnel events. The Java side
// (QualitySummary.Nat) and the telemetry ingest's allowed list carry the
// same two strings; change all three together.
//
// gonc distinguishes three shapes (easy / hard / symm); the wire keeps two.
// "symm" (symmetric: a different mapped port per STUN server) is reported
// as hard — for the punch it is the hard case, only harder. Adding a
// "symm" value on the wire needs the ingest redeployed first.
const (
	natEasy = "easy"
	natHard = "hard"
)

// natProbeTimeout bounds the whole probe. The probe is telemetry only: it
// never sits on the readiness path, so it can and must be cut short rather
// than hold the terminal event's Nat field hostage (takeNat in cmdTunnel
// simply finds nothing when this expires first).
const natProbeTimeout = 6 * time.Second

// natProbeNetworks is what the probe asks STUN about. IPv4 only: there is
// no NAT to classify on IPv6, and gonc's traversal treats v6 as direct.
// Order is preference when both answer — UDP is the classic hole-punching
// case and the one the historical easy/hard data was measured on.
var natProbeNetworks = []string{"udp4", "tcp4"}

// natProbe is one prepared NAT classification (newNatProbe + run).
type natProbe struct {
	servers int
}

// newNatProbe applies the credential's STUN list (gonc syntax, the
// backend's own stunServers parameter; gonc's built-in candidates when
// absent) and returns the probe to run later.
//
// This must happen before the backend goroutine and the probe goroutine
// start: the probe rides on easyp2p, whose server lists are package
// globals that the gonc backend's Run also applies. Setting them once here,
// from the same params, means neither goroutine writes while the other
// reads (goncp2p.ApplyServerLists is a no-op when the values already match).
func newNatProbe(params map[string]string) natProbe {
	goncp2p.ApplyServerLists(params)
	return natProbe{servers: len(easyp2p.STUNServers)}
}

// run classifies the local NAT through gonc's own STUN prober and returns
// the wire value, or "" when the probe was inconclusive (the event then
// omits the field, "unknown" downstream). The punch does its own STUN
// round inside easyp2p; this result feeds telemetry only.
func (p natProbe) run(ctx context.Context, diagf func(format string, args ...any)) string {
	ctx, cancel := context.WithTimeout(ctx, natProbeTimeout)
	defer cancel()
	started := time.Now()
	// easyp2p's own progress lines are dropped: the backend already logs
	// the punch-time STUN round to the tunnel log, this would duplicate it.
	addrs, _, err := easyp2p.DetectNATAddressInfoContext(ctx, natProbeNetworks, "", nil, io.Discard)
	network, natType, ok := pickNatType(addrs)
	if !ok {
		diagf("%s", i18n.T("nat.probeFailed", len(addrs), p.servers, err))
		return ""
	}
	wire := natWire(natType)
	if wire == "" {
		diagf("%s", i18n.T("nat.classifyFailed", natType, network))
		return ""
	}
	diagf("%s", i18n.T("nat.classified",
		wire, natType, network, p.servers, time.Since(started).Milliseconds()))
	return wire
}

// pickNatType selects the STUN verdict to report: the first entry for the
// most preferred network in natProbeNetworks. Entries for other networks
// (v6, relay) are ignored.
func pickNatType(addrs []easyp2p.PunchingAddressInfo) (network, natType string, ok bool) {
	for _, want := range natProbeNetworks {
		for _, a := range addrs {
			if a.Network == want {
				return a.Network, a.NatType, true
			}
		}
	}
	return "", "", false
}

// natWire maps gonc's NAT type to the wire value; anything else maps to ""
// (field omitted).
func natWire(natType string) string {
	switch natType {
	case "easy":
		return natEasy
	case "hard", "symm":
		return natHard
	default:
		return ""
	}
}
