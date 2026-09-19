package libcore

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	quic "github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	boxservice "github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport/fakeip"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing-box/protocol/mixed"
	"github.com/sagernet/sing-box/protocol/vless"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/service"
)

func startQUICPathBox(t *testing.T, raw string) (*box.Box, context.Context) {
	t.Helper()
	ir, or := inbound.NewRegistry(), outbound.NewRegistry()
	vless.RegisterInbound(ir)
	mixed.RegisterInbound(ir)
	vless.RegisterOutbound(or)
	direct.RegisterOutbound(or)
	group.RegisterWeighted(or)
	dr := dns.NewTransportRegistry()
	local.RegisterTransport(dr)
	fakeip.RegisterTransport(dr)
	ctx := box.Context(context.Background(), ir, or, endpoint.NewRegistry(), dr, boxservice.NewRegistry(), certificate.NewRegistry())
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, []byte(raw)); err != nil {
		t.Fatal(err)
	}
	b, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	if err = b.Start(); err != nil {
		b.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close() })
	return b, ctx
}

func reserveTCPPort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	return port
}

// Real encrypted HTTP/3 over SOCKS UDP -> FakeIP routing -> VLESS -> UDP.
// This covers packet framing and hostname preservation, not Android radios.
func TestHTTP3ThroughFakeIPVLESS(t *testing.T) {
	testHTTP3ThroughFakeIPVLESS(t, false)
}

func TestHTTP3ThroughFakeIPVLESSWithPacketLoss(t *testing.T) {
	testHTTP3ThroughFakeIPVLESS(t, true)
}

type auditLossPacketConn struct {
	net.PacketConn
	reads   atomic.Uint32
	writes  atomic.Uint32
	dropped *atomic.Uint32
}

func (c *auditLossPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	for {
		n, addr, err := c.PacketConn.ReadFrom(p)
		if err == nil && c.reads.Add(1)%17 == 0 {
			c.dropped.Add(1)
			continue
		}
		return n, addr, err
	}
}

func (c *auditLossPacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	if c.writes.Add(1)%17 == 0 {
		c.dropped.Add(1)
		return len(p), nil
	}
	return c.PacketConn.WriteTo(p, addr)
}

func testHTTP3ThroughFakeIPVLESS(t *testing.T, withLoss bool) {
	var dropped atomic.Uint32
	defer func() {
		if withLoss {
			t.Logf("intentionally dropped UDP packets: %d", dropped.Load())
			if dropped.Load() == 0 {
				t.Error("loss scenario did not exercise packet loss")
			}
		}
	}()
	certServer := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	cert := certServer.TLS.Certificates[0]
	roots := x509.NewCertPool()
	roots.AddCert(certServer.Certificate())
	certServer.Close()
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	payload := bytes.Repeat([]byte("vload-http3-integrity-"), 8192)
	h3 := &http3.Server{TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{"h3"}}, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write(payload) })}
	serveDone := make(chan struct{})
	go func() { h3.Serve(packet); close(serveDone) }()
	defer func() { h3.Close(); packet.Close(); <-serveDone }()
	destinationPort := packet.LocalAddr().(*net.UDPAddr).Port
	for _, mux := range []bool{false, true} {
		for _, tfo := range []bool{false, true} {
			t.Run(fmt.Sprintf("mux=%v/tfo=%v", mux, tfo), func(t *testing.T) {
				port := reserveTCPPort(t)
				const uuid = "22222222-2222-4222-8222-222222222222"
				startQUICPathBox(t, fmt.Sprintf(`{"log":{"level":"error"},"dns":{"servers":[{"type":"local","tag":"local"}]},"route":{"default_domain_resolver":{"server":"local","strategy":"ipv4_only"}},"inbounds":[{"type":"vless","listen":"127.0.0.1","listen_port":%d,"tcp_fast_open":%v,"users":[{"uuid":"%s"}],"multiplex":{"enabled":true}}],"outbounds":[{"type":"direct","tag":"direct"}]}`, port, tfo, uuid))
				for _, balanced := range []bool{false, true} {
					t.Run(fmt.Sprintf("balanced=%v", balanced), func(t *testing.T) {
						mixedPort := reserveTCPPort(t)
						final := "primary"
						if balanced {
							final = "balanced"
						}
						member := func(tag string) string {
							return fmt.Sprintf(`{"type":"vless","tag":"%s","server":"127.0.0.1","server_port":%d,"uuid":"%s","tcp_fast_open":%v,"multiplex":{"enabled":%v,"protocol":"yamux","max_connections":24,"min_streams":4,"padding":true}}`, tag, port, uuid, tfo, mux)
						}
						client, ctx := startQUICPathBox(t, fmt.Sprintf(`{"log":{"level":"error"},"dns":{"servers":[{"type":"local","tag":"local"},{"type":"fakeip","tag":"fake","inet4_range":"198.18.0.0/15"}]},"inbounds":[{"type":"mixed","listen":"127.0.0.1","listen_port":%d}],"outbounds":[%s,%s,{"type":"weighted","tag":"balanced","outbounds":[{"outbound":"primary","weight":50},{"outbound":"secondary","weight":50}]}],"route":{"final":"%s"}}`, mixedPort, member("primary"), member("secondary"), final))
						fake, err := service.FromContext[adapter.DNSTransportManager](ctx).FakeIP().Store().Create("localhost", false)
						if err != nil {
							t.Fatal(err)
						}
						peer := net.UDPAddrFromAddrPort(M.Socksaddr{Addr: fake, Port: uint16(destinationPort)}.AddrPort())
						sd := socks.NewClient(N.SystemDialer, M.ParseSocksaddr(fmt.Sprintf("127.0.0.1:%d", mixedPort)), socks.Version5, "", "")
						for phase := 0; phase < 2; phase++ {
							transport := &http3.Transport{TLSClientConfig: &tls.Config{RootCAs: roots, ServerName: "example.com"}, QUICConfig: &quic.Config{DisablePathMTUDiscovery: true}, Dial: func(ctx context.Context, _ string, tc *tls.Config, qc *quic.Config) (*quic.Conn, error) {
								pc, e := sd.ListenPacket(ctx, M.SocksaddrFromNet(peer))
								if e != nil {
									return nil, e
								}
								t.Cleanup(func() { pc.Close() })
								if withLoss {
									pc = &auditLossPacketConn{PacketConn: pc, dropped: &dropped}
								}
								conn, e := quic.Dial(ctx, pc, peer, tc, qc)
								if e != nil {
									pc.Close()
								}
								return conn, e
							}}
							hc := &http.Client{Transport: transport, Timeout: 5 * time.Second}
							var wg sync.WaitGroup
							for i := 0; i < 6; i++ {
								wg.Add(1)
								go func() {
									defer wg.Done()
									r, e := hc.Get(fmt.Sprintf("https://example.com:%d/test", destinationPort))
									if e != nil {
										t.Error(e)
										return
									}
									defer r.Body.Close()
									body, e := io.ReadAll(r.Body)
									if e != nil || r.ProtoMajor != 3 || !bytes.Equal(body, payload) {
										t.Errorf("HTTP/3 payload failed: proto=%d bytes=%d err=%v", r.ProtoMajor, len(body), e)
									}
								}()
							}
							wg.Wait()
							transport.Close()
							if phase == 0 {
								primary, _ := client.Outbound().Outbound("primary")
								primary.(adapter.InterfaceUpdateListener).InterfaceUpdated(context.Background())
								if balanced {
									ob, _ := client.Outbound().Outbound("balanced")
									w := ob.(*group.Weighted)
									w.UpdateAvailability(0, false)
									w.CloseMember(0)
								}
							}
						}
					})
				}
			})
		}
	}
}
