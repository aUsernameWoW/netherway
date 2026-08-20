//go:build nogonc

package main

// frp-only variant: gonc-p2p is not compiled in.

func serveGonc(map[string]string, int, int, string) error {
	return backendNotBuilt(goncP2pName)
}
