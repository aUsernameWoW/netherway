package main

// The single registration point for tunnel backends: adding a scheme is one
// implementation package plus one Register line here (and a serve case in
// main.go if the scheme has a publish side). The registry is looked up by
// name, so the credential's backendId picks the implementation and an
// unknown id fails with tunnel.unknownBackend listing what this build has.

import (
	"github.com/aUsernameWoW/netherway/internal/backend"
	"github.com/aUsernameWoW/netherway/internal/backend/goncp2p"
)

// defaultBackendName is what serve and tunnel use when -backend is absent.
const defaultBackendName = goncp2p.Name

func init() {
	backend.Register(goncp2p.New())
}
