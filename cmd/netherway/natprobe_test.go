package main

import (
	"testing"

	"github.com/threatexpert/gonc/v2/easyp2p"
)

// TestNatWireContract pins the gonc → wire mapping. The wire values line up
// byte for byte with the Java side QualitySummary.Nat and the ingest's
// allowed list; symmetric NAT is folded into "hard" until the ingest grows
// a value for it.
func TestNatWireContract(t *testing.T) {
	cases := map[string]string{
		"easy":  "easy",
		"hard":  "hard",
		"symm":  "hard",
		"relay": "",
		"":      "",
		"new":   "",
	}
	for in, want := range cases {
		if got := natWire(in); got != want {
			t.Errorf("natWire(%q) = %q, want %q", in, got, want)
		}
	}
}

// TestPickNatType pins the network preference: udp4 over tcp4, IPv6 and
// relay entries ignored, nothing usable → not ok.
func TestPickNatType(t *testing.T) {
	addr := func(network, nat string) easyp2p.PunchingAddressInfo {
		return easyp2p.PunchingAddressInfo{Network: network, NatType: nat}
	}
	cases := []struct {
		name    string
		addrs   []easyp2p.PunchingAddressInfo
		network string
		nat     string
		ok      bool
	}{
		{"udp preferred", []easyp2p.PunchingAddressInfo{addr("tcp4", "hard"), addr("udp4", "easy")}, "udp4", "easy", true},
		{"tcp fallback", []easyp2p.PunchingAddressInfo{addr("tcp6", "easy"), addr("tcp4", "symm")}, "tcp4", "symm", true},
		{"v6 only", []easyp2p.PunchingAddressInfo{addr("tcp6", "easy")}, "", "", false},
		{"empty", nil, "", "", false},
	}
	for _, c := range cases {
		network, nat, ok := pickNatType(c.addrs)
		if network != c.network || nat != c.nat || ok != c.ok {
			t.Errorf("%s: got (%q, %q, %v), want (%q, %q, %v)",
				c.name, network, nat, ok, c.network, c.nat, c.ok)
		}
	}
}
