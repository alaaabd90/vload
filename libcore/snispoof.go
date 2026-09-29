package libcore

import (
	"encoding/json"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/option"
	"strings"
)

// SniSpoofCandidates returns bounded, sampled candidates; it sends no probes.
// The editor tests them with TestInstance, through the actual saved protocol.
func SniSpoofCandidates(primary, settingsJSON string) (string, error) {
	var settings option.SNISpoofOptions
	if err := json.Unmarshal([]byte(settingsJSON), &settings); err != nil {
		return "", err
	}
	return strings.Join(tls.SNICandidates(primary, settings), "\n"), nil
}
