package libcore

import (
	"context"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"testing"
)

type cardTestOutbound struct {
	*slotTestOutbound
	tag string
}

func (o *cardTestOutbound) Tag() string       { return o.tag }
func (o *cardTestOutbound) Network() []string { return []string{"tcp", "udp"} }

func TestCardSwitchPreservesOtherSlotAndGroupMethods(t *testing.T) {
	manager := &slotTestManager{outbounds: make(map[string]adapter.Outbound)}
	ctx := service.ContextWithDefaultRegistry(context.Background())
	service.MustRegister[adapter.OutboundManager](ctx, manager)
	wifi := &cardTestOutbound{&slotTestOutbound{t: t}, "wifi"}
	zain := &cardTestOutbound{&slotTestOutbound{t: t}, "zain"}
	asia := &cardTestOutbound{&slotTestOutbound{t: t}, "asia"}
	for _, o := range []*cardTestOutbound{wifi, zain, asia} {
		manager.outbounds[o.Tag()] = o
	}
	raw, err := group.NewSelector(ctx, nil, logger.NOP(), "cards", option.SelectorOutboundOptions{Outbounds: []string{"zain", "asia"}})
	if err != nil {
		t.Fatal(err)
	}
	selector := raw.(*group.Selector)
	manager.outbounds["cards"] = selector
	if err = selector.Start(); err != nil {
		t.Fatal(err)
	}
	var primary *group.Weighted
	for _, tag := range []string{"proxy", "dns-proxy", "quic-proxy"} {
		raw, err = group.NewWeighted(ctx, nil, logger.NOP(), tag, option.WeightedOutboundOptions{Mode: "priority", Outbounds: []option.WeightedOutboundMember{{Outbound: "wifi"}, {Outbound: "cards"}}})
		if err != nil {
			t.Fatal(err)
		}
		w := raw.(*group.Weighted)
		manager.outbounds[tag] = w
		if err = w.Start(); err != nil {
			t.Fatal(err)
		}
		if primary == nil {
			primary = w
		}
	}
	slots := findSlotGroups(primary, manager)
	dial := func(w *group.Weighted) {
		t.Helper()
		c, e := w.DialContext(ctx, "tcp", M.ParseSocksaddr("example.com:443"))
		if e != nil {
			t.Fatal(e)
		}
		t.Cleanup(func() { c.Close() })
	}
	for _, w := range slots {
		dial(w)
	}
	slots.updateAvailability(0, false)
	for _, w := range slots {
		dial(w)
	}
	if len(zain.conns) != 3 {
		t.Fatal("all groups must use first card")
	}
	slots.updateAvailability(1, false)
	slots.closeMember(1)
	if !selectSlotOutbound(manager, primary, 1, "asia") {
		t.Fatal("card selection failed")
	}
	if selectSlotOutbound(manager, primary, 1, "wifi") || selectSlotOutbound(manager, primary, 0, "asia") || selectSlotOutbound(manager, primary, 2, "asia") {
		t.Fatal("invalid card selection accepted")
	}
	for _, c := range wifi.conns {
		if c.closed.Load() {
			t.Fatal("Wi-Fi connection interrupted")
		}
	}
	for _, c := range zain.conns {
		if !c.closed.Load() {
			t.Fatal("old SIM connection survived")
		}
	}
	slots.updateAvailability(1, true)
	for _, w := range slots {
		dial(w)
	}
	if len(asia.conns) != 3 {
		t.Fatal("traffic, DNS and QUIC must all follow new card")
	}
	slots.updateAvailability(1, false)
	slots.updateAvailability(0, true)
	for _, w := range slots {
		dial(w)
	}
	if selector.Now() != "asia" {
		t.Fatal("network availability changed card unexpectedly")
	}
}
