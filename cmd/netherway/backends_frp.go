//go:build !nofrp

package main

import (
	"github.com/aUsernameWoW/netherway/internal/backend"
	"github.com/aUsernameWoW/netherway/internal/backend/frpxtcp"
)

func init() {
	backend.Register(frpxtcp.New())
}
