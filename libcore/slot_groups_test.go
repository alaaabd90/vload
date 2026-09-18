package libcore

import (
	"context"
	"net"
	"sync/atomic"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

type slotTestManager struct {
	adapter.OutboundManager
	outbounds map[string]adapter.Outbound
}

func (m *slotTestManager) Outbound(tag string) (adapter.Outbound, bool) {
	outbound, ok := m.outbounds[tag]
	return outbound, ok
}

type slotTestConn struct {
	net.Conn
	closed atomic.Bool
}

func (c *slotTestConn) Close() error {
	c.closed.Store(true)
	return c.Conn.Close()
}

type slotTestOutbound struct {
	adapter.Outbound
	t     *testing.T
	conns []*slotTestConn
}

func (o *slotTestOutbound) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	client, server := net.Pipe()
	o.t.Cleanup(func() { server.Close() })
	conn := &slotTestConn{Conn: client}
	o.conns = append(o.conns, conn)
	return conn, nil
}

func TestSlotLossAndRecoveryReachTrafficDNSAndQUIC(t *testing.T) {
	manager := &slotTestManager{outbounds: make(map[string]adapter.Outbound)}
	ctx := service.ContextWithDefaultRegistry(context.Background())
	service.MustRegister[adapter.OutboundManager](ctx, manager)
	a := &slotTestOutbound{t: t}
	b := &slotTestOutbound{t: t}
	manager.outbounds["a"], manager.outbounds["b"] = a, b
	var groups []*group.Weighted
	for _, tag := range []string{"proxy", "dns-proxy", "quic-proxy"} {
		outbound, err := group.NewWeighted(ctx, nil, logger.NOP(), tag, option.WeightedOutboundOptions{
			Mode:      "priority",
			Outbounds: []option.WeightedOutboundMember{{Outbound: "a"}, {Outbound: "b"}},
		})
		if err != nil {
			t.Fatal(err)
		}
		weighted := outbound.(*group.Weighted)
		manager.outbounds[tag] = weighted
		if err := weighted.Start(); err != nil {
			t.Fatal(err)
		}
		groups = append(groups, weighted)
	}
	slots := findSlotGroups(groups[0], manager)
	dialAll := func(want string) {
		t.Helper()
		for _, weighted := range groups {
			conn, err := weighted.DialContext(ctx, "tcp", M.ParseSocksaddr("example.com:443"))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { conn.Close() })
			if weighted.Now() != want {
				t.Fatalf("%s used %s, want %s", weighted.Tag(), weighted.Now(), want)
			}
		}
	}
	dialAll("a")
	slots.updateAvailability(0, false)
	dialAll("b")
	if closed := slots.closeMember(0); closed != 3 {
		t.Fatalf("closed %d connections, want 3", closed)
	}
	for _, conn := range a.conns {
		if !conn.closed.Load() {
			t.Fatal("lost slot connection remained open")
		}
	}
	for _, conn := range b.conns {
		if conn.closed.Load() {
			t.Fatal("healthy slot connection was closed")
		}
	}
	slots.updateAvailability(0, true)
	dialAll("a")
}

func TestSlotGroupsIgnoreSingleProfile(t *testing.T) {
	if groups := findSlotGroups(nil, nil); len(groups) != 0 {
		t.Fatal("single profile must not create slot groups")
	}
}
