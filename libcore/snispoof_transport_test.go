package libcore

import (
	"bytes"
	"context"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/http/httptest"
	"net/netip"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/service"
	stls "github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing-box/protocol/vless"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/json/badoption"
	M "github.com/sagernet/sing/common/metadata"
)

// Exercise the actual VLESS transports and multiplex implementation against a
// local TLS server. No Internet access, user profiles, or DPI bypass claims.
func TestSNISpoofVLESSTransports(t *testing.T) {
	certServer := httptest.NewTLSServer(nil)
	cert := certServer.TLS.Certificates[0]
	certServer.Close()
	keyBytes, err := x509.MarshalPKCS8PrivateKey(cert.PrivateKey)
	if err != nil {
		t.Fatal(err)
	}
	certPEM := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert.Certificate[0]}))
	keyPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyBytes}))
	echConfig, echKey, err := stls.ECHKeygenDefault("example.com")
	if err != nil {
		t.Fatal(err)
	}
	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		for {
			conn, err := echo.Accept()
			if err != nil {
				return
			}
			go func() { defer conn.Close(); io.Copy(conn, conn) }()
		}
	}()
	start := func(t *testing.T, opts option.Options) *box.Box {
		t.Helper()
		opts.Log = &option.LogOptions{Level: "error"}
		in, out := inbound.NewRegistry(), outbound.NewRegistry()
		vless.RegisterInbound(in)
		vless.RegisterOutbound(out)
		direct.RegisterOutbound(out)
		dr := dns.NewTransportRegistry()
		local.RegisterTransport(dr)
		ctx := box.Context(context.Background(), in, out, endpoint.NewRegistry(), dr, service.NewRegistry(), certificate.NewRegistry())
		instance, err := box.New(box.Options{Context: ctx, Options: opts})
		if err != nil {
			t.Fatal(err)
		}
		if err = instance.Start(); err != nil {
			instance.Close()
			t.Fatal(err)
		}
		t.Cleanup(func() { instance.Close() })
		return instance
	}
	const uuid = "11111111-1111-4111-8111-111111111111"
	for _, transport := range []string{"", "ws", "grpc", "http", "httpupgrade"} {
		for _, mux := range []bool{false, true} {
			for _, ech := range []bool{false, true} {
				t.Run(fmt.Sprintf("transport=%s/mux=%v/ech=%v", transport, mux, ech), func(t *testing.T) {
					reservation, err := net.Listen("tcp", "127.0.0.1:0")
					if err != nil {
						t.Fatal(err)
					}
					port := uint16(reservation.Addr().(*net.TCPAddr).Port)
					reservation.Close()
					var tr *option.V2RayTransportOptions
					if transport != "" {
						tr = &option.V2RayTransportOptions{Type: transport, HTTPOptions: option.V2RayHTTPOptions{Path: "/vpn"}, WebsocketOptions: option.V2RayWebsocketOptions{Path: "/vpn"}, GRPCOptions: option.V2RayGRPCOptions{ServiceName: "vpn"}, HTTPUpgradeOptions: option.V2RayHTTPUpgradeOptions{Path: "/vpn"}}
					}
					serverTLS := &option.InboundTLSOptions{Enabled: true, Certificate: []string{certPEM}, Key: []string{keyPEM}, ALPN: []string{"h2", "http/1.1"}}
					clientTLS := &option.OutboundTLSOptions{Enabled: true, ServerName: "example.com", Certificate: []string{certPEM}, SNISpoof: &option.SNISpoofOptions{Strategy: "record", FragmentSize: 128, MaxCandidates: 3, CandidateIPs: []string{"127.0.0.2", "127.0.0.1"}, AttemptTimeoutMS: 1000, TotalTimeoutMS: 5000, RequireECH: ech}}
					if ech {
						serverTLS.ECH = &option.InboundECHOptions{Enabled: true, Key: []string{echKey}}
						clientTLS.ECH = &option.OutboundECHOptions{Enabled: true, Config: []string{echConfig}}
					}
					start(t, option.Options{Inbounds: []option.Inbound{{Type: "vless", Options: &option.VLESSInboundOptions{ListenOptions: option.ListenOptions{Listen: common.Ptr(badoption.Addr(netip.MustParseAddr("127.0.0.1"))), ListenPort: port}, Users: []option.VLESSUser{{UUID: uuid}}, InboundTLSOptionsContainer: option.InboundTLSOptionsContainer{TLS: serverTLS}, Transport: tr, Multiplex: &option.InboundMultiplexOptions{Enabled: true}}}}, Outbounds: []option.Outbound{{Type: "direct", Tag: "direct", Options: &option.DirectOutboundOptions{}}}})
					client := start(t, option.Options{Outbounds: []option.Outbound{{Type: "vless", Tag: "proxy", Options: &option.VLESSOutboundOptions{ServerOptions: option.ServerOptions{Server: "127.0.0.2", ServerPort: port}, UUID: uuid, OutboundTLSOptionsContainer: option.OutboundTLSOptionsContainer{TLS: clientTLS}, Transport: tr, Multiplex: &option.OutboundMultiplexOptions{Enabled: mux, Protocol: "yamux", MaxConnections: 2}}}}})
					proxy, ok := client.Outbound().Outbound("proxy")
					if !ok {
						t.Fatal("missing outbound")
					}
					for phase := 0; phase < 2; phase++ {
						ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
						conn, err := proxy.DialContext(ctx, "tcp", M.ParseSocksaddr(echo.Addr().String()))
						if err != nil {
							cancel()
							t.Fatal(err)
						}
						conn.SetDeadline(time.Now().Add(8 * time.Second))
						payload := []byte("verified VLESS traffic after SNISpoof handshake")
						_, err = conn.Write(payload)
						if err == nil {
							data := make([]byte, len(payload))
							_, err = io.ReadFull(conn, data)
							if err == nil && !bytes.Equal(payload, data) {
								t.Error("payload changed")
							}
						}
						conn.Close()
						cancel()
						if err != nil {
							t.Fatal(err)
						}
						proxy.(adapter.InterfaceUpdateListener).InterfaceUpdated(context.Background())
					}
				})
			}
		}
	}
}
