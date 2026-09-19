package libcore

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"testing"
	"time"

	mdns "github.com/miekg/dns"
	quic "github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/protocol/socks"
)

// Explicit opt-in: run against a separately started local core using a private
// profile. No credentials or profile contents are embedded in this test.
func TestExternalFakeDNSTransports(t *testing.T) {
	address := os.Getenv("VLOAD_AUDIT_SOCKS")
	if address == "" {
		t.Skip("set VLOAD_AUDIT_SOCKS to test an authorized local runner")
	}
	sd := socks.NewClient(N.SystemDialer, M.ParseSocksaddr(address), socks.Version5, "", "")
	for _, host := range []string{"www.google.com", "www.youtube.com"} {
		t.Run(host, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
			defer cancel()
			dc, err := sd.DialContext(ctx, "tcp", M.ParseSocksaddr("1.1.1.1:53"))
			if err != nil {
				t.Fatal(err)
			}
			dc.SetDeadline(time.Now().Add(8 * time.Second))
			defer dc.Close()
			dnsConn := &mdns.Conn{Conn: dc}
			query := new(mdns.Msg).SetQuestion(host+".", mdns.TypeA)
			if err = dnsConn.WriteMsg(query); err != nil {
				t.Fatal(err)
			}
			answer, err := dnsConn.ReadMsg()
			if err != nil {
				t.Fatal(err)
			}
			var ip net.IP
			for _, rr := range answer.Answer {
				if a, ok := rr.(*mdns.A); ok {
					ip = a.A
					break
				}
			}
			if ip == nil || ip.To4() == nil || ip.To4()[0] != 198 || ip.To4()[1] != 18 {
				t.Fatal("runner did not return an IPv4 FakeIP")
			}
			peer := &net.UDPAddr{IP: ip, Port: 443}
			destination := M.SocksaddrFromNet(peer)
			for _, h3 := range []bool{false, true} {
				t.Run(fmt.Sprintf("http3=%v", h3), func(t *testing.T) {
					var rt http.RoundTripper
					if h3 {
						transport := &http3.Transport{QUICConfig: &quic.Config{DisablePathMTUDiscovery: true, HandshakeIdleTimeout: 5 * time.Second}, Dial: func(ctx context.Context, _ string, tc *tls.Config, qc *quic.Config) (*quic.Conn, error) {
							pc, e := sd.ListenPacket(ctx, destination)
							if e != nil {
								return nil, e
							}
							t.Cleanup(func() { pc.Close() })
							c, e := quic.Dial(ctx, pc, peer, tc, qc)
							if e != nil {
								pc.Close()
							}
							return c, e
						}}
						defer transport.Close()
						rt = transport
					} else {
						transport := &http.Transport{ForceAttemptHTTP2: true, DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
							return sd.DialContext(ctx, network, destination)
						}}
						defer transport.CloseIdleConnections()
						rt = transport
					}
					started := time.Now()
					client := &http.Client{Transport: rt, Timeout: 8 * time.Second}
					response, e := client.Get("https://" + host + "/robots.txt")
					if e != nil {
						t.Fatal(e)
					}
					defer response.Body.Close()
					n, e := io.Copy(io.Discard, io.LimitReader(response.Body, 2<<20))
					if e != nil {
						t.Fatal(e)
					}
					if h3 && response.ProtoMajor != 3 {
						t.Fatal("not HTTP/3")
					}
					t.Logf("protocol=%s status=%d bytes=%d elapsed=%s", response.Proto, response.StatusCode, n, time.Since(started))
				})
			}
		})
	}
}
