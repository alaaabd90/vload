package libcore

import (
	"context"
	"testing"

	"github.com/sagernet/sing-box/adapter"
)

type resetTestOutbound struct {
	adapter.Outbound
	dependencies []string
	resets       int
}

func (o *resetTestOutbound) Dependencies() []string           { return o.dependencies }
func (o *resetTestOutbound) InterfaceUpdated(context.Context) { o.resets++ }

func TestResetLostSlotTransportDoesNotResetHealthySlot(t *testing.T) {
	a := &resetTestOutbound{dependencies: []string{"a-transport"}}
	aTransport := &resetTestOutbound{}
	b := &resetTestOutbound{}
	m := &slotTestManager{outbounds: map[string]adapter.Outbound{
		"a": a, "a-transport": aTransport, "b": b,
	}}
	resetOutboundTransports(context.Background(), m, []string{"a", "a", "a"})
	if a.resets != 1 || aTransport.resets != 1 || b.resets != 0 {
		t.Fatalf("reset counts: a=%d transport=%d healthy=%d", a.resets, aTransport.resets, b.resets)
	}
}
