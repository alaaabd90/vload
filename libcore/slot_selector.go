package libcore

import (
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/protocol/group"
)

// SelectSlotOutbound only changes the profile underneath an existing weighted
// member. The caller disables that slot and drains its old transports first.
// Traffic, DNS and QUIC continue to share the same two members and algorithms.
func (b *BoxInstance) SelectSlotOutbound(slot int32, tag string) bool {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != 1 || b.weighted == nil || slot < 0 || slot > 1 {
		return false
	}
	return selectSlotOutbound(b.Outbound(), b.weighted, int(slot), tag)
}

func selectSlotOutbound(manager adapter.OutboundManager, weighted *group.Weighted, slot int, tag string) bool {
	if weighted == nil || slot < 0 || slot > 1 {
		return false
	}
	members := weighted.All()
	if int(slot) >= len(members) {
		return false
	}
	outbound, found := manager.Outbound(members[slot])
	if !found {
		return false
	}
	selector, ok := outbound.(*group.Selector)
	return ok && selector.SelectOutbound(tag)
}
