// Package backend defines the tunnel backend interface and registry.
//
// A backend reduces one tunnel scheme to a single promise: open a TCP port
// on this machine at the requested address that leads to the Minecraft
// server. gonc-p2p is the implementation shipped today; any future scheme
// that can be squeezed into the "local port" shape plugs in without
// touching the mod side or the tunnel subcommand.
//
// Readiness is deliberately not reported by the backend itself: the caller
// probes with a Minecraft handshake (Server List Ping), a criterion that
// holds for every scheme and additionally proves the server process is
// answering, not merely that a port is listening.
package backend

import (
	"context"
	"io"
	"sort"

	"github.com/aUsernameWoW/netherway/internal/config"
)

// Options are the scheme-independent run parameters the caller hands to a
// backend.
type Options struct {
	// BindAddr/BindPort is the local TCP listener the backend must open;
	// connecting to it is connecting to the Minecraft server.
	BindAddr string
	BindPort int
	// Timings, normalized by the caller.
	Timings config.Timings
	// LogLevel/LogTo direct the backend's own log output. In tunnel mode
	// stdout carries the line-delimited JSON status contract, so the
	// backend log must go to a file. Backends without log levels ignore
	// LogLevel.
	LogLevel string
	LogTo    string
	// Logf receives the backend's diagnostics — effective parameters,
	// ignored keys, server selection: the "why did it end up here"
	// information that complements the raw log in LogTo. tunnel mode points
	// it at stderr, which the mod collects into the game log; nil discards.
	// Contract: never print parameter values such as keys or tokens.
	Logf func(format string, args ...any)
	// LogEcho, when non-nil, receives a copy of the backend library's own
	// log lines in addition to the LogTo file. tunnel mode points it at
	// stderr so a failure only the tunnel library can explain still reaches
	// the game log.
	LogEcho io.Writer
}

// Diagf writes a diagnostic line through Logf, quietly dropping it when
// Logf is nil.
func (o Options) Diagf(format string, args ...any) {
	if o.Logf != nil {
		o.Logf(format, args...)
	}
}

// Backend is one tunnel scheme.
//
// Contract:
//   - Run blocks until ctx is canceled or the tunnel fails unrecoverably,
//     and releases every resource before returning. Parameter validation
//     happens inside Run too, failing as early as possible.
//   - No relay fallback. tunnel mode decides "probe succeeded = punched";
//     a fallback channel would make the probe succeed unconditionally and
//     hide whether the tunnel was ever established.
//   - Unknown keys in params must be ignored: the server may be updated
//     before the agent, and extra parameters must not fail an older agent.
type Backend interface {
	// Name is the identifier used in credentials and on the command line,
	// e.g. "gonc-p2p".
	Name() string
	// Run establishes the tunnel and keeps it up until ctx is canceled. The
	// param keys are each implementation's own contract, mirrored by the
	// corresponding Java-side Credentials factory.
	Run(ctx context.Context, params map[string]string, opts Options) error
}

var registry = map[string]Backend{}

// Register adds a backend; the cmd side calls it from its registration
// point. A duplicate name is a build mistake and panics at startup.
func Register(b Backend) {
	name := b.Name()
	if _, dup := registry[name]; dup {
		panic("backend 重复注册: " + name)
	}
	registry[name] = b
}

// Lookup finds a registered backend by name.
func Lookup(name string) (Backend, bool) {
	b, ok := registry[name]
	return b, ok
}

// Names lists the registered backend names in lexical order, for error
// messages.
func Names() []string {
	out := make([]string, 0, len(registry))
	for name := range registry {
		out = append(out, name)
	}
	sort.Strings(out)
	return out
}
