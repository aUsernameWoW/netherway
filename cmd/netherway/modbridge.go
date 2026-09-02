package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/aUsernameWoW/netherway/internal/backend"
	"github.com/aUsernameWoW/netherway/internal/config"
	"github.com/aUsernameWoW/netherway/internal/i18n"
	"github.com/aUsernameWoW/netherway/internal/mcping"
)

// The tunnel subcommand is what the Minecraft mod runs as a child process.
//
//   - No relay fallback: the player is already on the server through the
//     existing relayed connection; a failed punch means staying there, not
//     opening another relay. A fallback would also make the readiness probe
//     succeed unconditionally, hiding whether the punch worked at all.
//   - stdout carries line-delimited JSON status for the mod to parse; the
//     backend's own logs go to a file (and are echoed to stderr).
//   - Not ready within the punch timeout: exit non-zero, the mod gives up.
//
// The tunnel scheme sits behind internal/backend. This file only picks a
// port, starts the backend, probes readiness with a Minecraft handshake and
// prints status JSON — none of which depends on the scheme. Backends are
// registered in backends.go.

type event struct {
	Event string `json:"event"`
	// Backend rides on "starting" only: which tunnel scheme this run uses,
	// for log forensics.
	Backend string `json:"backend,omitempty"`
	Port    int    `json:"port,omitempty"`
	// ElapsedMs is process start to tunnel usable, including setup before the
	// punch; the mod uses it to decide whether to tell the player a direct
	// connection is being established.
	ElapsedMs int64 `json:"elapsedMs,omitempty"`
	// RTTMs is measured separately after readiness, without setup cost, so
	// it compares directly against the relayed route.
	RTTMs   int64  `json:"rttMs,omitempty"`
	Version string `json:"version,omitempty"`
	Online  int    `json:"online,omitempty"`
	// FailureStage/FailureCode are stable, low-cardinality fields for
	// statistics; Reason is free text for local diagnostics only and must
	// never become a telemetry dimension or be uploaded.
	FailureStage string `json:"failureStage,omitempty"`
	FailureCode  string `json:"failureCode,omitempty"`
	// Nat is the NAT shape (easy/hard) classified via STUN, attached to the
	// terminal event when known and omitted otherwise. See natprobe.go.
	Nat    string `json:"nat,omitempty"`
	Reason string `json:"reason,omitempty"`
}

// Event names on the wire. Besides these, "degraded" (with Port) is a
// reserved advisory event: a backend able to report that a READY tunnel has
// stopped working without exiting may emit it after "ready", and the mod
// keeps handling it (tear down and rebuild the warmup tunnel). No backend
// in this build emits it; a backend that cannot tell simply exits, which
// the mod treats the same way via "stopped".
const (
	eventStarting = "starting"
	eventReady    = "ready"
	eventFailed   = "failed"
	eventStopped  = "stopped"
)

const (
	failureStageStart   = "start"
	failureStageBackend = "backend"
	failureStageProbe   = "probe"

	failureCodeBackendUnknown    = "backend_unknown"
	failureCodeBindPortFailed    = "bind_port_failed"
	failureCodeBackendExited     = "backend_exited"
	failureCodeReadyProbeTimeout = "ready_probe_timeout"
)

func failedEvent(stage, code, reason string) event {
	return event{
		Event:        eventFailed,
		FailureStage: stage,
		FailureCode:  code,
		Reason:       reason,
	}
}

// measureRTT pings a few times and keeps the minimum: the first connection
// through a fresh tunnel carries negotiation overhead and would badly
// overestimate latency. Returns 0 when every sample fails ("unknown" to
// the mod).
//
// Sampling must finish before the deadline: the mod only waits
// punchTimeout+startupGrace, and a slow punch that becomes ready near the
// end would push the ready event out of that window if we then spent
// 3×probeTimeout sampling. Fewer or no samples (rtt=0) beats delaying
// ready.
func measureRTT(port int, timeout time.Duration, deadline time.Time) int64 {
	const samples = 3
	best := int64(0)
	for range samples {
		remain := time.Until(deadline)
		if remain <= 0 {
			break
		}
		if remain < timeout {
			timeout = remain
		}
		_, rtt, err := mcping.Ping("127.0.0.1", port, timeout)
		if err != nil {
			continue
		}
		ms := rtt.Milliseconds()
		if best == 0 || ms < best {
			best = ms
		}
	}
	return best
}

func emit(e event) {
	b, err := json.Marshal(e)
	if err != nil {
		return
	}
	fmt.Fprintln(os.Stdout, string(b))
}

// paramFlags collects repeatable -O key=value.
type paramFlags map[string]string

func (p paramFlags) String() string {
	return i18n.T("tunnel.paramCount", len(p))
}

func (p paramFlags) Set(s string) error {
	eq := strings.IndexByte(s, '=')
	if eq <= 0 {
		return i18n.Errorf("tunnel.badParamFormat", s)
	}
	p[s[:eq]] = s[eq+1:]
	return nil
}

func sortedKeys(m map[string]string) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func cmdTunnel(args []string) error {
	fs := flag.NewFlagSet("tunnel", flag.ExitOnError)
	backendName := fs.String("backend", defaultBackendName, i18n.T("flag.tunnel.backend"))
	params := paramFlags{}
	fs.Var(params, "O", i18n.T("flag.tunnel.param"))
	verbose := fs.Bool("v", false, i18n.T("flag.verbose"))
	wantPort := fs.Int("port", 0, i18n.T("flag.tunnel.port"))
	// Measured: a smooth punch is ready in a few seconds, but the slow path
	// (re-punch) is much longer and 12 s can be borderline. The player is
	// playing over the relay meanwhile and this wait is in the background,
	// so erring long is cheaper than a false failure.
	//
	// All timings are overridable: player networks differ widely, and the
	// mod passes its configured values through these flags.
	d := config.DefaultTimings()
	timeout := fs.Float64("timeout", d.PunchTimeout.Seconds(), i18n.T("flag.tunnel.timeout"))
	probeInterval := fs.Float64("probe-interval", d.ProbeInterval.Seconds(), i18n.T("flag.tunnel.probeInterval"))
	probeTimeout := fs.Float64("probe-timeout", d.ProbeTimeout.Seconds(), i18n.T("flag.tunnel.probeTimeout"))
	retryInterval := fs.Float64("retry-interval", d.RetryMinInterval.Seconds(), i18n.T("flag.tunnel.retryInterval"))
	maxRetries := fs.Int("max-retries-hour", d.MaxRetriesAnHour, i18n.T("flag.tunnel.maxRetries"))
	logPath := fs.String("log-file", "", i18n.T("flag.tunnel.logFile"))
	if err := fs.Parse(args); err != nil {
		return err
	}

	b, ok := backend.Lookup(*backendName)
	if !ok {
		err := i18n.Errorf("tunnel.unknownBackend",
			*backendName, strings.Join(backend.Names(), ", "))
		emit(failedEvent(failureStageStart, failureCodeBackendUnknown, err.Error()))
		return err
	}
	merged := map[string]string(params)

	// Default to an automatically assigned port: the player's machine may
	// well have its own 25565 open, and the mod reads the port from the
	// status output anyway.
	port, err := pickPort(*wantPort)
	if err != nil {
		emit(failedEvent(failureStageStart, failureCodeBindPortFailed, err.Error()))
		return err
	}

	if *logPath == "" {
		*logPath = filepath.Join(os.TempDir(), "netherway-tunnel.log")
	}

	// Diagnostics go to stderr: stdout is reserved for the JSON contract,
	// and the mod forwards stderr into the game log, so being chatty here
	// is harmless.
	diagf := func(format string, args ...any) {
		fmt.Fprintf(os.Stderr, format+"\n", args...)
	}
	diagf("%s", i18n.T("tunnel.diagParams",
		b.Name(), port, strings.Join(sortedKeys(merged), ", ")))
	diagf("%s", i18n.T("tunnel.diagTimings",
		*timeout, *probeInterval, *probeTimeout, *logPath, logLevelOf(*verbose)))

	ctx, stop := signalContext()
	defer stop()

	started := time.Now()
	emit(event{Event: eventStarting, Backend: b.Name(), Port: port})

	// NAT classification runs in the background and nobody waits for it:
	// the terminal event carries the result if it is in by then, otherwise
	// the field is omitted. Telemetry only, never a punch decision. The
	// probe is prepared (STUN list applied) before either goroutine starts,
	// see newNatProbe.
	nat := newNatProbe(merged)
	natCh := make(chan string, 1)
	go func() {
		natCh <- nat.run(ctx, diagf)
	}()
	takeNat := func() string {
		select {
		case v := <-natCh:
			return v
		default:
			return ""
		}
	}

	secs := func(v float64) time.Duration { return time.Duration(v * float64(time.Second)) }
	timings := config.Timings{
		PunchTimeout:     secs(*timeout),
		ProbeInterval:    secs(*probeInterval),
		ProbeTimeout:     secs(*probeTimeout),
		RetryMinInterval: secs(*retryInterval),
		MaxRetriesAnHour: *maxRetries,
	}.Normalize()

	tunnelErr := make(chan error, 1)
	go func() {
		tunnelErr <- b.Run(ctx, merged, backend.Options{
			BindAddr: "127.0.0.1",
			BindPort: port,
			Timings:  timings,
			LogLevel: logLevelOf(*verbose),
			LogTo:    *logPath,
			Logf:     diagf,
			// The backend's own log lines are echoed to stderr and reach
			// the game log through the mod: a failure only the tunnel
			// library can explain no longer lives in a file alone.
			LogEcho: os.Stderr,
		})
	}()

	// With no fallback channel, a successful probe means the tunnel really
	// is up — the reason the backend interface forbids fallbacks.
	deadline := time.Now().Add(timings.PunchTimeout)
	ready := make(chan *mcping.Status, 1)
	rttCh := make(chan time.Duration, 1)
	probeErr := make(chan error, 1)
	go func() {
		st, rtt, err := mcping.WaitReady("127.0.0.1", port, deadline,
			timings.ProbeInterval, timings.ProbeTimeout)
		if err != nil {
			probeErr <- err
			return
		}
		rttCh <- rtt
		ready <- st
	}()

	select {
	case err := <-tunnelErr:
		reason := i18n.T("tunnel.exitedEarly")
		if err != nil {
			reason = err.Error()
		}
		ev := failedEvent(failureStageBackend, failureCodeBackendExited, reason)
		ev.Nat = takeNat()
		emit(ev)
		return fmt.Errorf("%s", reason)

	case err := <-probeErr:
		ev := failedEvent(failureStageProbe, failureCodeReadyProbeTimeout,
			i18n.T("tunnel.probeFailed", err))
		ev.Nat = takeNat()
		emit(ev)
		return i18n.Errorf("tunnel.notReadyIn", *timeout)

	case st := <-ready:
		<-rttCh // the first probe includes setup time; useless as latency
		e := event{
			Event:     eventReady,
			Port:      port,
			ElapsedMs: time.Since(started).Milliseconds(),
		}
		if st != nil {
			e.Version = st.Version.Name
			e.Online = st.Players.Online
		}
		// The first connection through a fresh tunnel is markedly slower
		// (2.4 s measured); several samples with the minimum kept reflect
		// the real round trip.
		e.RTTMs = measureRTT(port, timings.ProbeTimeout, deadline)
		e.Nat = takeNat()
		emit(e)
	}

	// Stay up after readiness until the mod ends the process (player
	// disconnects or quits). A dead session makes the backend return, which
	// is reported as "stopped" with the reason; the mod then rebuilds.
	select {
	case <-ctx.Done():
		emit(event{Event: eventStopped})
		return nil
	case err := <-tunnelErr:
		emit(event{Event: eventStopped, Reason: fmt.Sprint(err)})
		return err
	}
}
