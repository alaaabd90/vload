package libcore

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync/atomic"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
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
	// Construct the actual core HTTPS transports directly. A full Box starts
	// a host interface monitor whose asynchronous initial network reset can
	// cancel the first query, unrelated to the slot behavior tested here.
	makeTransport := func(server *httptest.Server, tag string) *transport.HTTPSTransport {
		t.Helper()
		u, err := url.Parse(server.URL + "/dns-query")
		if err != nil {
			t.Fatal(err)
		}
		tlsConfig, err := tls.NewSTDClient(context.Background(), logger.NOP(), "127.0.0.1", option.OutboundTLSOptions{Insecure: true})
		if err != nil {
			t.Fatal(err)
		}
		return transport.NewHTTPSRaw(dns.NewTransportAdapter("https", tag, nil), logger.NOP(), N.SystemDialer, u, make(http.Header), M.ParseSocksaddr(u.Host), tlsConfig)
	}
	wifiTransport, lteTransport := makeTransport(wifi, "wifi"), makeTransport(lte, "lte")
	defer wifiTransport.Close()
	defer lteTransport.Close()
	fallback, _, _ := testDNSFailover(t, 0, dnsAnswer, dnsAnswer)
	fallback.members = []adapter.DNSTransport{wifiTransport, lteTransport}
	fallback.attemptTimeout = 150 * time.Millisecond
	fallback.retryInterval = 10 * time.Second
	remote := fallback
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
