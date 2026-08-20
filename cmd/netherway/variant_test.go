//go:build !nofrp && !nogonc

package main

import (
	"testing"

	"github.com/aUsernameWoW/netherway/internal/backend/frpxtcp"
	"github.com/aUsernameWoW/netherway/internal/backend/goncp2p"
)

// TestBackendNameMirrors pins the literals redeclared for variant builds
// (-tags nofrp / nogonc cannot import the excluded backend package) to the
// real constants. Runs on the default build only, which links both.
func TestBackendNameMirrors(t *testing.T) {
	if frpXtcpName != frpxtcp.Name {
		t.Errorf("frpXtcpName = %q, frpxtcp.Name = %q", frpXtcpName, frpxtcp.Name)
	}
	if goncP2pName != goncp2p.Name {
		t.Errorf("goncP2pName = %q, goncp2p.Name = %q", goncP2pName, goncp2p.Name)
	}
	if natProbeSTUNKey != frpxtcp.ParamSTUN {
		t.Errorf("natProbeSTUNKey = %q, frpxtcp.ParamSTUN = %q", natProbeSTUNKey, frpxtcp.ParamSTUN)
	}
	if defaultBackendName != frpxtcp.Name {
		t.Errorf("default build should default to frp-xtcp, got %q", defaultBackendName)
	}
}
