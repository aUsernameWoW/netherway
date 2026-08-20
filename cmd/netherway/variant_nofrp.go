//go:build nofrp

package main

import (
	"flag"

	"github.com/aUsernameWoW/netherway/internal/config"
)

// gonc-only variant: frp-xtcp is not compiled in. The stubs below answer
// for every frp-specific seam so the shared code stays tag-free.

const defaultBackendName = goncP2pName

func serveFrp(*endpointOpts, *config.Room, int, int, string, string, string, bool) error {
	return backendNotBuilt(frpXtcpName)
}

// legacySugarParams: the old frp flags stay registered for help-text
// stability but there is no frp backend to feed them to.
func legacySugarParams(*flag.FlagSet, string) map[string]string {
	return map[string]string{}
}

// probeNat: NAT classification rides on frp's STUN prober (pkg/nathole);
// without it the telemetry dimension is simply omitted (empty = unknown),
// same as when every STUN candidate fails.
func probeNat(string, func(format string, args ...any)) string {
	return ""
}
