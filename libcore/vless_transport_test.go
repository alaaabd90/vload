package libcore

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing-box/protocol/vless"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
)

// Local transport coverage only: this does not emulate Android radios, TLS,
// remote DNS policy, or the user's servers.
func TestVLESSMuxTFOAndTransportReset(t *testing.T) {
	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		for {
			c, e := echo.Accept()
			if e != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	destination := M.ParseSocksaddr(echo.Addr().String())
	const uuid = "22222222-2222-4222-8222-222222222222"
	start := func(t *testing.T, options option.Options) *box.Box {
		t.Helper()
		options.Log = &option.LogOptions{Level: "error"}
		inRegistry, outRegistry := inbound.NewRegistry(), outbound.NewRegistry()
		vless.RegisterInbound(inRegistry)
		vless.RegisterOutbound(outRegistry)
		direct.RegisterOutbound(outRegistry)
		group.RegisterWeighted(outRegistry)
		dnsRegistry := dns.NewTransportRegistry()
		local.RegisterTransport(dnsRegistry)
		ctx := box.Context(context.Background(), inRegistry, outRegistry, endpoint.NewRegistry(), dnsRegistry, service.NewRegistry(), certificate.NewRegistry())
		instance, e := box.New(box.Options{Context: ctx, Options: options})
		if e != nil {
			t.Fatal(e)
		}
		if e = instance.Start(); e != nil {
			instance.Close()
			t.Fatal(e)
		}
		t.Cleanup(func() { instance.Close() })
		return instance
	}
	for _, mux := range []bool{false, true} {
		for _, tfo := range []bool{false, true} {
			t.Run(fmt.Sprintf("mux=%v/tfo=%v", mux, tfo), func(t *testing.T) {
				reservation, e := net.Listen("tcp", "127.0.0.1:0")
				if e != nil {
					t.Fatal(e)
				}
				port := uint16(reservation.Addr().(*net.TCPAddr).Port)
				reservation.Close()
				start(t, option.Options{
					Inbounds: []option.Inbound{{Type: "vless", Options: &option.VLESSInboundOptions{
						ListenOptions: option.ListenOptions{Listen: common.Ptr(badoption.Addr(netip.MustParseAddr("127.0.0.1"))), ListenPort: port, TCPFastOpen: tfo},
						Users:         []option.VLESSUser{{UUID: uuid}}, Multiplex: &option.InboundMultiplexOptions{Enabled: true},
					}}},
					Outbounds: []option.Outbound{{Type: "direct", Tag: "direct", Options: &option.DirectOutboundOptions{}}},
				})
				clientOptions := option.Options{Outbounds: []option.Outbound{{Type: "vless", Tag: "proxy", Options: &option.VLESSOutboundOptions{
					DialerOptions: option.DialerOptions{AbstractDialerOptions: option.AbstractDialerOptions{TCPFastOpen: tfo}},
					ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: port}, UUID: uuid,
					Multiplex: &option.OutboundMultiplexOptions{Enabled: mux, Protocol: "yamux", MaxConnections: 24, MinStreams: 4, Padding: true},
				}}, {Type: "weighted", Tag: "balanced", Options: &option.WeightedOutboundOptions{
					Outbounds: []option.WeightedOutboundMember{{Outbound: "proxy", Weight: 50}, {Outbound: "secondary", Weight: 50}},
				}}}}
				secondary := clientOptions.Outbounds[0]
				secondary.Tag = "secondary"
				clientOptions.Outbounds = append(clientOptions.Outbounds, secondary)
				client := start(t, clientOptions)
				outbound, _ := client.Outbound().Outbound("proxy")
				balanced, _ := client.Outbound().Outbound("balanced")
				for phase := 0; phase < 2; phase++ {
					t.Logf("transfer phase %d (after reset=%v)", phase, phase > 0)
					dialer := outbound
					if phase == 1 {
						dialer = balanced
					}
					var wg sync.WaitGroup
					for i := 0; i < 24; i++ {
						wg.Add(1)
						go func() {
							defer wg.Done()
							ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
							defer cancel()
							c, e := dialer.DialContext(ctx, "tcp", destination)
							if e != nil {
								t.Error(e)
								return
							}
							defer c.Close()
							c.SetDeadline(time.Now().Add(5 * time.Second))
							payload := bytes.Repeat([]byte("vload-transport-test"), 1024)
							if _, e = c.Write(payload); e != nil {
								t.Error(e)
								return
							}
							received := make([]byte, len(payload))
							if _, e = io.ReadFull(c, received); e != nil {
								t.Error(e)
								return
							}
							if !bytes.Equal(payload, received) {
								t.Error("corrupted payload")
							}
						}()
					}
					wg.Wait()
					if phase == 0 {
						outbound.(adapter.InterfaceUpdateListener).InterfaceUpdated(context.Background())
					}
				}
			})
		}
	}
}
