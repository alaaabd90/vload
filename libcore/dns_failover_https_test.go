package libcore

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	boxService "github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing/service"
)

// Exercise actual HTTP/TLS request cancellation and pooled DoH connections:
// a successful TCP connection alone is not proof that DNS is reachable.
func TestDNSFailoverWithRealHTTPSBlackhole(t *testing.T) {
	testDNSWithRealHTTPSBlackhole(t, false)
}

func TestParallelDNSWithRealHTTPSBlackhole(t *testing.T) {
	testDNSWithRealHTTPSBlackhole(t, true)
}

func testDNSWithRealHTTPSBlackhole(t *testing.T, parallel bool) {
	var wifiStalled atomic.Bool
	var wifiCalls, lteCalls atomic.Int32
	server := func(wifi bool) *httptest.Server {
		return httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if wifi {
				wifiCalls.Add(1)
			} else {
				lteCalls.Add(1)
			}
			data, err := io.ReadAll(r.Body)
			if err != nil {
				return
			}
			if wifi && wifiStalled.Load() {
				<-r.Context().Done()
				return
			}
			var query mDNS.Msg
			if query.Unpack(data) != nil {
				w.WriteHeader(400)
				return
			}
			response := new(mDNS.Msg).SetReply(&query)
			response.Answer = []mDNS.RR{&mDNS.A{Hdr: mDNS.RR_Header{Name: query.Question[0].Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60}, A: net.IPv4(203, 0, 113, 10)}}
			packed, _ := response.Pack()
			w.Header().Set("Content-Type", "application/dns-message")
			w.Write(packed)
		}))
	}
	wifi, lte := server(true), server(false)
	defer wifi.Close()
	defer lte.Close()
	port := func(s *httptest.Server) int {
		_, p, _ := net.SplitHostPort(s.Listener.Addr().String())
		v, _ := strconv.Atoi(p)
		return v
	}
	registry := dns.NewTransportRegistry()
	transport.RegisterHTTPS(registry)
	dns.RegisterTransport[slotDNSOptions](registry, "vload_dns", newSlotDNSTransport)
	outRegistry := outbound.NewRegistry()
	direct.RegisterOutbound(outRegistry)
	ctx := service.ContextWithDefaultRegistry(context.Background())
	ctx = box.Context(ctx, inbound.NewRegistry(), outRegistry, endpoint.NewRegistry(), registry, boxService.NewRegistry(), certificate.NewRegistry())
	raw := fmt.Sprintf(`{"log":{"level":"error"},"dns":{"servers":[{"type":"https","tag":"wifi","server":"127.0.0.1","server_port":%d,"tls":{"insecure":true}},{"type":"https","tag":"lte","server":"127.0.0.1","server_port":%d,"tls":{"insecure":true}},{"type":"vload_dns","tag":"dns-remote","servers":["wifi","lte"],"attempt_timeout":"150ms","retry_interval":"10s"}],"final":"dns-remote"},"outbounds":[{"type":"direct","tag":"direct"}]}`, port(wifi), port(lte))
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, []byte(raw)); err != nil {
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	if err = instance.Start(); err != nil {
		instance.Close()
		t.Fatal(err)
	}
	defer instance.Close()
	manager := service.FromContext[adapter.DNSTransportManager](ctx)
	remote, _ := manager.Transport("dns-remote")
	fallback := remote.(*slotDNSTransport)
	fallback.parallel = parallel
	check := func() {
		t.Helper()
		r, err := remote.Exchange(context.Background(), dnsQuestion())
		if err != nil || r == nil || r.Rcode != mDNS.RcodeSuccess || len(r.Answer) != 1 {
			t.Fatalf("DoH failed: response=%v error=%v", r, err)
		}
	}
	if parallel {
		// Prime Wi-Fi's actual HTTPS pool before blackholing it. Then race
		// against LTE with a very long per-path timeout: LTE must still win.
		fallback.UpdateNetworkAvailability(1, false)
		check()
		wifiStalled.Store(true)
		fallback.UpdateNetworkAvailability(1, true)
		fallback.attemptTimeout = time.Minute
		bounded, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		r, err := remote.Exchange(bounded, dnsQuestion())
		if err != nil || r == nil || r.Rcode != mDNS.RcodeSuccess || len(r.Answer) != 1 {
			t.Fatalf("parallel DoH waited for broken pool: %v", err)
		}
		if lteCalls.Load() != 1 {
			t.Fatal("LTE did not supply answer")
		}
		fallback.UpdateNetworkAvailability(0, false)
		check()
		wifiStalled.Store(false)
		fallback.UpdateNetworkAvailability(0, true)
		fallback.UpdateNetworkAvailability(1, false)
		check()
		return
	}
	check()
	if wifiCalls.Load() != 1 || lteCalls.Load() != 0 {
		t.Fatal("healthy Wi-Fi did not retain DNS priority")
	}
	wifiStalled.Store(true)
	start := time.Now()
	check()
	if time.Since(start) > time.Second {
		t.Fatal("pooled HTTPS blackhole exceeded fallback bound")
	}
	check()
	if wifiCalls.Load() != 2 || lteCalls.Load() != 2 {
		t.Fatal("blackholed HTTP pool was retried immediately")
	}
	fallback.UpdateNetworkAvailability(0, false)
	check()
	wifiStalled.Store(false)
	fallback.UpdateNetworkAvailability(0, true)
	check()
	if wifiCalls.Load() != 3 || lteCalls.Load() != 3 {
		t.Fatal("Wi-Fi preference did not recover after disconnect/reconnect")
	}
}
