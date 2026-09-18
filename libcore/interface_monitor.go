package libcore

import (
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	"github.com/sagernet/sing/common/x/list"
)

// Android's VpnService/slot controller owns availability and connection resets.
// This adapter supplies no native interface snapshot or native callbacks.
type interfaceMonitorStub struct{}

func (s *interfaceMonitorStub) PlatformManagesNetworkState() bool { return true }

func (s *interfaceMonitorStub) Start() error {
	return nil
}

func (s *interfaceMonitorStub) Close() error {
	return nil
}

func (s *interfaceMonitorStub) DefaultInterface() *control.Interface {
	return nil
}

func (s *interfaceMonitorStub) OverrideAndroidVPN() bool {
	return false
}

func (s *interfaceMonitorStub) AndroidVPNEnabled() bool {
	return false
}

func (s *interfaceMonitorStub) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	return nil
}

func (s *interfaceMonitorStub) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
}

func (s *interfaceMonitorStub) RegisterMyInterface(interfaceName string) {
}

func (s *interfaceMonitorStub) MyInterfaces() []string {
	return nil
}
