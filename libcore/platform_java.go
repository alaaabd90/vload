package libcore

var intfBox BoxPlatformInterface
var intfNB4A NB4AInterface

var useProcfs bool
var isBgProcess bool

type NB4AInterface interface {
	UseOfficialAssets() bool
	Selector_OnProxySelected(selectorTag string, tag string)
}

type BoxPlatformInterface interface {
	AutoDetectInterfaceControl(fd int32) error
	// AutoDetectInterfaceControlSlot is like AutoDetectInterfaceControl but
	// for one of the two vload load-balance network slots (0 or 1): the
	// implementation should bind fd to that slot's specific android.net.Network
	// (via Network.bindSocket) rather than protecting it onto whatever the OS
	// currently treats as the default network.
	AutoDetectInterfaceControlSlot(fd int32, slot int32) error
	OpenTun(singTunOptionsJson, tunPlatformOptionsJson string) (int, error)
	UseProcFS() bool
	FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (int32, error)
	PackageNameByUid(uid int32) (string, error)
	UIDByPackageName(packageName string) (int32, error)
	WIFIState() string
}
