package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/boxapi"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

// Package-level dialer.DoNotSelectInterface was removed upstream in
// sing-box 1.14.0 (its own network-strategy interface selection is now
// only ever enabled when a config explicitly sets network_strategy/
// network_type - see common/dialer/default.go). vload's own outbounds
// never set those, and instead pick interfaces via each weighted-outbound
// member's own bind_interface, so nothing here needs to force that off
// any more - it's already off by default.

var mainInstance atomic.Pointer[BoxInstance]

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	if system {
		if instance := mainInstance.Load(); instance != nil {
			instance.access.Lock()
			defer instance.access.Unlock()
			if instance.state == 2 {
				return
			}
			if cm := service.FromContext[adapter.ConnectionManager](instance.ctx); cm != nil {
				cm.CloseAll()
			}
			var tags []string
			for _, outbound := range instance.Outbound().Outbounds() {
				tags = append(tags, outbound.Tag())
			}
			resetOutboundTransports(instance.ctx, instance.Outbound(), tags)
		}
		log.Println("Reset system connections done")
	} else {
		log.Println("TODO: Reset user connections")
	}
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	ctx    context.Context
	cancel context.CancelFunc
	state  int

	v2api        *boxapi.SbV2rayServer
	selector     *group.Selector
	weighted     *group.Weighted
	pauseManager pause.Manager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		return nil, fmt.Errorf("decode config: %v", err)
	}

	// create box
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:          instance,
		ctx:          ctx,
		cancel:       cancel,
		pauseManager: service.FromContext[pause.Manager](ctx),
	}

	// selector / weighted (vload load-balance)
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		switch outbound := proxy.(type) {
		case *group.Selector:
			b.selector = outbound
		case *group.Weighted:
			b.weighted = outbound
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == 0 {
		b.state = 1
		return b.Box.Start()
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		return nil
	}
	b.state = 2

	// clear main instance
	if mainInstance.CompareAndSwap(b, nil) {
		goServeProtect(false)
	}

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.Box != nil {
		b.Box.Close()
	}

	return nil
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstance.Store(b)
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	b.v2api = boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api.StatsService())
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	return b.v2api.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if b.selector != nil {
		return b.selector.SelectOutbound(tag)
	}
	return false
}

// UpdateNetworkAvailability marks one of the two vload load-balance slots
// (0 or 1, matching the order the weighted outbound's members were
// configured in) as available or unavailable, for failover when the
// underlying physical network it's bound to drops or recovers. No-op if
// the running config isn't using a weighted (vload) outbound.
func (b *BoxInstance) UpdateNetworkAvailability(slot int32, available bool) {
	findSlotGroups(b.weighted, b.Outbound()).updateAvailability(int(slot), available)
}

// ResetSlotConnections closes every connection currently open on the given
// vload load-balance slot (0 or 1) and returns how many it closed. Use this
// instead of ResetAllConnections when only one slot's physical network
// actually changed - a global reset also kills the other, unaffected slot's
// perfectly healthy connections, which is unnecessary collateral damage a
// routine network change (cell handover, Wi-Fi roaming) shouldn't cause. A
// negative return means the running config isn't using a weighted (vload)
// outbound, so there was nothing slot-scoped to do.
func (b *BoxInstance) ResetSlotConnections(slot int32) int32 {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state == 2 {
		return 0
	}
	if b.weighted == nil {
		return -1
	}
	groups := findSlotGroups(b.weighted, b.Outbound())
	closed := groups.closeMember(int(slot))
	var tags []string
	for _, weighted := range groups {
		members := weighted.All()
		if slot >= 0 && int(slot) < len(members) {
			tags = append(tags, members[slot])
		}
	}
	resetOutboundTransports(b.ctx, b.Outbound(), tags)
	return int32(closed)
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	var connectionTracker adapter.ConnectionTracker
	// test i
	if i != nil {
		if i.v2api != nil {
			connectionTracker = i.v2api.StatsService()
		}
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(i.Box, connectionTracker), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// test direct
	instance := mainInstance.Load()
	if instance == nil {
		return speedtest.UrlTest(boxapi.CreateProxyHttpClient(nil, nil), link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// test mainInstance
	if instance.v2api != nil {
		connectionTracker = instance.v2api.StatsService()
	}
	return speedtest.UrlTest(boxapi.CreateProxyHttpClient(instance.Box, connectionTracker), link, timeout, speedtest.UrlTestStandard_RTT)
}

var protectCloser io.Closer
var protectSlotClosers [2]io.Closer

// vload slot-specific protect paths. Each member of a "weighted" outbound
// sets protect_path to one of these in its DialerOptions (see ConfigBuilder.kt),
// so its dialer's fd arrives at the matching listener below instead of the
// generic one, letting Kotlin bind it to a specific network (Network.bindSocket)
// rather than whatever ConnectivityManager currently treats as default.
var protectSlotPaths = [2]string{"protect_path_a", "protect_path_b"}

func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	for i, closer := range protectSlotClosers {
		if closer != nil {
			closer.Close()
			protectSlotClosers[i] = nil
		}
	}
	if start {
		protectCloser = protect_server.ServeProtectWithError("protect_path", false, 0, func(fd int) error {
			return intfBox.AutoDetectInterfaceControl(int32(fd))
		})
		for i, path := range protectSlotPaths {
			slot := int32(i)
			protectSlotClosers[i] = protect_server.ServeProtectWithError(path, false, 0, func(fd int) error {
				return intfBox.AutoDetectInterfaceControlSlot(int32(fd), slot)
			})
		}
	}
}
