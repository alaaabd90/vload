package libcore

import (
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/protocol/group"
)

type slotGroups []*group.Weighted

// All three groups share physical slots. DNS and QUIC need the same
// availability and connection-reset notifications as ordinary traffic.
func findSlotGroups(primary *group.Weighted, manager adapter.OutboundManager) slotGroups {
	if primary == nil {
		return nil
	}
	groups := slotGroups{primary}
	for _, tag := range []string{"dns-proxy", "quic-proxy"} {
		if outbound, loaded := manager.Outbound(tag); loaded {
			if weighted, ok := outbound.(*group.Weighted); ok {
				groups = append(groups, weighted)
			}
		}
	}
	return groups
}

func (groups slotGroups) updateAvailability(slot int, available bool) {
	for _, weighted := range groups {
		weighted.UpdateAvailability(slot, available)
	}
}

func (groups slotGroups) closeMember(slot int) int {
	var closed int
	for _, weighted := range groups {
		closed += weighted.CloseMember(slot)
	}
	return closed
}
