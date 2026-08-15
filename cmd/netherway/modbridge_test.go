package main

import (
	"encoding/json"
	"testing"
)

func TestFailedEventContract(t *testing.T) {
	tests := []struct {
		name      string
		stage     string
		wantStage string
		code      string
		wantCode  string
	}{
		{"unknown backend", failureStageStart, "start",
			failureCodeBackendUnknown, "backend_unknown"},
		{"bind port", failureStageStart, "start",
			failureCodeBindPortFailed, "bind_port_failed"},
		{"backend exited", failureStageBackend, "backend",
			failureCodeBackendExited, "backend_exited"},
		{"ready probe timeout", failureStageProbe, "probe",
			failureCodeReadyProbeTimeout, "ready_probe_timeout"},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if tt.stage != tt.wantStage || tt.code != tt.wantCode {
				t.Fatalf("contract constants = %q/%q, want %q/%q",
					tt.stage, tt.code, tt.wantStage, tt.wantCode)
			}
			encoded, err := json.Marshal(failedEvent(tt.stage, tt.code, "local detail"))
			if err != nil {
				t.Fatal(err)
			}
			var got map[string]any
			if err := json.Unmarshal(encoded, &got); err != nil {
				t.Fatal(err)
			}
			if got["event"] != "failed" {
				t.Fatalf("event = %v", got["event"])
			}
			if got["failureStage"] != tt.stage {
				t.Fatalf("failureStage = %v, want %q", got["failureStage"], tt.stage)
			}
			if got["failureCode"] != tt.code {
				t.Fatalf("failureCode = %v, want %q", got["failureCode"], tt.code)
			}
			if got["reason"] != "local detail" {
				t.Fatalf("reason = %v", got["reason"])
			}
		})
	}
}

func TestNatWireContract(t *testing.T) {
	// 线上值与 Java 侧 QualitySummary.Nat、ingest 的 allowed 列表逐字对齐
	if natWire("EasyNAT") != "easy" || natWire("HardNAT") != "hard" {
		t.Fatalf("natWire mapping broken: %q/%q", natWire("EasyNAT"), natWire("HardNAT"))
	}
	if natWire("SomethingNew") != "" {
		t.Fatalf("unknown NatType should map to empty, got %q", natWire("SomethingNew"))
	}

	encoded, err := json.Marshal(event{Event: "ready", Port: 63128, Nat: "easy"})
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(encoded, &got); err != nil {
		t.Fatal(err)
	}
	if got["nat"] != "easy" {
		t.Fatalf("nat = %v", got["nat"])
	}
}

func TestNonFailureEventOmitsFailureClassification(t *testing.T) {
	encoded, err := json.Marshal(event{Event: "ready", Port: 63128})
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal(encoded, &got); err != nil {
		t.Fatal(err)
	}
	if _, ok := got["failureStage"]; ok {
		t.Fatalf("ready event unexpectedly contains failureStage: %s", encoded)
	}
	if _, ok := got["failureCode"]; ok {
		t.Fatalf("ready event unexpectedly contains failureCode: %s", encoded)
	}
	if _, ok := got["nat"]; ok {
		t.Fatalf("ready event without probe result unexpectedly contains nat: %s", encoded)
	}
}
