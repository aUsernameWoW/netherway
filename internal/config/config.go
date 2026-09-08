// Package config holds the tunable timing parameters shared by the tunnel
// command and the backends.
package config

import "time"

// Timings gathers every time parameter in one place.
//
// Deliberately not hard-coded at the use sites: player networks differ
// widely, the mod's config file must be able to override each value, and
// the mod passes them to the agent on the command line. Defaults come from
// real-machine measurements (a smooth punch completes in a few seconds).
type Timings struct {
	// PunchTimeout is the overall punch budget; on expiry the upgrade is
	// abandoned and the player stays on the existing relayed connection.
	PunchTimeout time.Duration
	// ProbeInterval separates two readiness probes.
	ProbeInterval time.Duration
	// ProbeTimeout bounds a single readiness probe.
	ProbeTimeout time.Duration
	// RetryMinInterval is the minimum pause after a failed punch attempt.
	RetryMinInterval time.Duration
	// MaxRetriesAnHour caps punch retries per hour so a flapping network
	// cannot hammer the STUN servers.
	MaxRetriesAnHour int
}

func DefaultTimings() Timings {
	return Timings{
		PunchTimeout:     15 * time.Second,
		ProbeInterval:    250 * time.Millisecond,
		ProbeTimeout:     2 * time.Second,
		RetryMinInterval: 90 * time.Second,
		MaxRetriesAnHour: 8,
	}
}

// Normalize replaces non-positive values with the defaults so a caller
// passing 0 cannot produce a busy loop or a probe that never fires.
func (t Timings) Normalize() Timings {
	d := DefaultTimings()
	if t.PunchTimeout <= 0 {
		t.PunchTimeout = d.PunchTimeout
	}
	if t.ProbeInterval <= 0 {
		t.ProbeInterval = d.ProbeInterval
	}
	if t.ProbeTimeout <= 0 {
		t.ProbeTimeout = d.ProbeTimeout
	}
	if t.RetryMinInterval <= 0 {
		t.RetryMinInterval = d.RetryMinInterval
	}
	if t.MaxRetriesAnHour <= 0 {
		t.MaxRetriesAnHour = d.MaxRetriesAnHour
	}
	return t
}
