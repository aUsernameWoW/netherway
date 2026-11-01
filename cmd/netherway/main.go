// netherway lets Minecraft players reach a server over a direct P2P
// connection. The hole-punching scheme is pluggable through
// internal/backend; gonc-p2p is the backend shipped today.
//
//	netherway serve    run on the server host: publishes the local
//	                   Minecraft port, optionally with an embedded
//	                   signaling broker on loopback (-rendezvous)
//	netherway tunnel   called by the Minecraft mod: punches and prints
//	                   line-delimited JSON status on stdout
//
// Credential prefetch before a player joins is not here: it is a Java-only
// exchange between the mod and the Minecraft server on the Minecraft port
// (core PreauthClient/PreauthService), never touching the agent and never
// opening another listening port on the server.
package main

import (
	"context"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/aUsernameWoW/netherway/internal/backend"
	"github.com/aUsernameWoW/netherway/internal/backend/goncp2p"
	"github.com/aUsernameWoW/netherway/internal/i18n"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "serve":
		err = cmdServe(os.Args[2:])
	case "tunnel":
		err = cmdTunnel(os.Args[2:])
	case "-h", "--help", "help":
		usage()
		return
	default:
		fmt.Fprintf(os.Stderr, "%s\n\n", i18n.T("main.unknownCommand", os.Args[1]))
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "%s\n", i18n.T("main.error", err))
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprint(os.Stderr, i18n.T("main.usage"))
}

func logLevelOf(verbose bool) string {
	if verbose {
		return "debug"
	}
	return "info"
}

// signalContext returns a context canceled on SIGINT/SIGTERM.
func signalContext() (context.Context, context.CancelFunc) {
	return signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
}

// checkProxyProtocol gives a plain-language error before the backend's own
// configuration validation gets to it.
func checkProxyProtocol(v string) error {
	switch v {
	case "", "v1", "v2":
		return nil
	default:
		return i18n.Errorf("serve.badProxyProtocol", v)
	}
}

func cmdServe(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	localPort := fs.Int("port", 25565, i18n.T("flag.serve.port"))
	proxyProtocol := fs.String("proxy-protocol", "", i18n.T("flag.serve.proxyProtocol"))
	rendezvousPort := fs.Int("rendezvous", 0, i18n.T("flag.serve.rendezvous"))
	backendName := fs.String("backend", defaultBackendName, i18n.T("flag.serve.backend"))
	params := paramFlags{}
	fs.Var(params, "O", i18n.T("flag.serve.param"))
	if err := fs.Parse(args); err != nil {
		return err
	}
	// serve is dispatched by name rather than through backend.Lookup: the
	// publish side of a backend is its own program (a wait loop, an embedded
	// broker), not the "open a local port" shape the Backend interface
	// describes. A new backend adds a case here next to its registration in
	// backends.go.
	switch *backendName {
	case goncp2p.Name:
		return serveGonc(params, *localPort, *rendezvousPort, *proxyProtocol)
	default:
		return i18n.Errorf("tunnel.unknownBackend",
			*backendName, strings.Join(backend.Names(), ", "))
	}
}

// reserveListener binds a loopback listener on want, falling back to a
// system-assigned free port when it is taken, and returns it still open.
// Holding the listener (instead of probing a port and closing it again)
// makes the reservation atomic: two agents on one machine, e.g. two modpacks
// started side by side that both ask for the same default port, can never
// both believe they own it. tunnel mode reports the actual port in the
// STARTING event, so the caller does not care which one it got.
func reserveListener(want int) (net.Listener, error) {
	if want > 0 && portFree(want) {
		if l, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", want)); err == nil {
			return l, nil
		}
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, i18n.Errorf("main.noFreePort", err)
	}
	return l, nil
}

func portFree(port int) bool {
	l, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", port))
	if err != nil {
		return false
	}
	l.Close()
	return true
}
