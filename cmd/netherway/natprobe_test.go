//go:build !nofrp

package main

import "testing"

// natWire rides on frp's nathole constants, so this test only exists on
// builds that include the frp backend (the gonc-only variant omits the
// nat telemetry dimension entirely).
func TestNatWireContract(t *testing.T) {
	// 线上值与 Java 侧 QualitySummary.Nat、ingest 的 allowed 列表逐字对齐
	if natWire("EasyNAT") != "easy" || natWire("HardNAT") != "hard" {
		t.Fatalf("natWire mapping broken: %q/%q", natWire("EasyNAT"), natWire("HardNAT"))
	}
	if natWire("SomethingNew") != "" {
		t.Fatalf("unknown NatType should map to empty, got %q", natWire("SomethingNew"))
	}
}
