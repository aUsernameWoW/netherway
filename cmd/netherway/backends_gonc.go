//go:build !nogonc

package main

import (
	"github.com/aUsernameWoW/netherway/internal/backend"
	"github.com/aUsernameWoW/netherway/internal/backend/goncp2p"
)

func init() {
	backend.Register(goncp2p.New())
}
