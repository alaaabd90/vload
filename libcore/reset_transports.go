package libcore

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
)

// Closing routed streams does not invalidate the mux/QUIC/WebSocket pool
// beneath them. Reset only the affected member and its detour dependencies.
func resetOutboundTransports(ctx context.Context, manager adapter.OutboundManager, tags []string) {
	seen := make(map[string]bool)
	var visit func(string)
	visit = func(tag string) {
		if seen[tag] {
			return
		}
		seen[tag] = true
		outbound, ok := manager.Outbound(tag)
		if !ok {
			return
		}
		for _, dependency := range outbound.Dependencies() {
			visit(dependency)
		}
		if listener, ok := outbound.(adapter.InterfaceUpdateListener); ok {
			listener.InterfaceUpdated(ctx)
		}
	}
	for _, tag := range tags {
		visit(tag)
	}
}
