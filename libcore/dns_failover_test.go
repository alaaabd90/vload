package libcore

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing/common/json/badoption"
	"github.com/sagernet/sing/common/logger"
)

type testDNSSlot struct {
	dns.TransportAdapter
	calls    atomic.Int32
	exchange func(context.Context, *mDNS.Msg) (*mDNS.Msg, error)
}

func (*testDNSSlot) Start(adapter.StartStage) error { return nil }
func (*testDNSSlot) Close() error                   { return nil }
func (*testDNSSlot) Reset()                         {}
func (s *testDNSSlot) Exchange(ctx context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
	s.calls.Add(1)
	return s.exchange(ctx, m)
}
func (s *testDNSSlot) ExchangeAsync(ctx context.Context, m *mDNS.Msg, cb func(*mDNS.Msg, error)) {
	cb(s.Exchange(ctx, m))
}
func dnsAnswer(_ context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
	return new(mDNS.Msg).SetReply(m), nil
}
func dnsBlackhole(ctx context.Context, _ *mDNS.Msg) (*mDNS.Msg, error) {
	<-ctx.Done()
	return nil, ctx.Err()
}
func dnsQuestion() *mDNS.Msg { return new(mDNS.Msg).SetQuestion("example.test.", mDNS.TypeA) }
func testDNSFailover(t *testing.T, preferred int, first, second func(context.Context, *mDNS.Msg) (*mDNS.Msg, error)) (*slotDNSTransport, *testDNSSlot, *testDNSSlot) {
	t.Helper()
	tr, err := newSlotDNSTransport(context.Background(), logger.NOP(), "dns-remote", slotDNSOptions{Servers: []string{"a", "b"}, Preferred: preferred, AttemptTimeout: badoption.Duration(40 * time.Millisecond), RetryInterval: badoption.Duration(time.Second)})
	if err != nil {
		t.Fatal(err)
	}
	a, b := &testDNSSlot{exchange: first}, &testDNSSlot{exchange: second}
	x := tr.(*slotDNSTransport)
	x.members = []adapter.DNSTransport{a, b}
	t.Cleanup(func() { x.Close() })
	return x, a, b
}
func TestDNSWiFiPreferencePreservesPhysicalSlotOrder(t *testing.T) {
	x, a, b := testDNSFailover(t, 1, dnsAnswer, dnsAnswer)
	if _, err := x.Exchange(context.Background(), dnsQuestion()); err != nil {
		t.Fatal(err)
	}
	if a.calls.Load() != 0 || b.calls.Load() != 1 {
		t.Fatal("Wi-Fi in slot 1 was not preferred")
	}
	x.UpdateNetworkAvailability(1, false)
	if _, err := x.Exchange(context.Background(), dnsQuestion()); err != nil {
		t.Fatal(err)
	}
	if a.calls.Load() != 1 || b.calls.Load() != 1 {
		t.Fatal("lost Wi-Fi slot was queried")
	}
	x.UpdateNetworkAvailability(1, true)
	if _, err := x.Exchange(context.Background(), dnsQuestion()); err != nil {
		t.Fatal(err)
	}
	if b.calls.Load() != 2 {
		t.Fatal("Wi-Fi recovery did not restore preference")
	}
}
func TestDNSBlackholeFallsBackAndDoesNotDelayEveryQuery(t *testing.T) {
	x, a, b := testDNSFailover(t, 0, dnsBlackhole, dnsAnswer)
	start := time.Now()
	if _, err := x.Exchange(context.Background(), dnsQuestion()); err != nil {
		t.Fatal(err)
	}
	if time.Since(start) > 500*time.Millisecond {
		t.Fatal("blackholed Wi-Fi was not bounded")
	}
	for i := 0; i < 10; i++ {
		if _, err := x.Exchange(context.Background(), dnsQuestion()); err != nil {
			t.Fatal(err)
		}
	}
	if a.calls.Load() != 1 || b.calls.Load() != 11 {
		t.Fatal("failed Wi-Fi retried on every DNS query")
	}
}
func TestDNSNetworkLossCancelsInFlightQuery(t *testing.T) {
	started := make(chan struct{})
	x, _, b := testDNSFailover(t, 0, func(ctx context.Context, m *mDNS.Msg) (*mDNS.Msg, error) { close(started); return dnsBlackhole(ctx, m) }, dnsAnswer)
	x.attemptTimeout = time.Minute
	done := make(chan error, 1)
	go func() { _, err := x.Exchange(context.Background(), dnsQuestion()); done <- err }()
	<-started
	x.UpdateNetworkAvailability(0, false)
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("loss did not interrupt DNS query")
	}
	if b.calls.Load() != 1 {
		t.Fatal("did not fail over to LTE")
	}
}
func TestDNSNegativeAnswerAndCallerCancellationDoNotFailOver(t *testing.T) {
	x, _, b := testDNSFailover(t, 0, func(_ context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
		r := new(mDNS.Msg).SetReply(m)
		r.Rcode = mDNS.RcodeNameError
		return r, nil
	}, dnsAnswer)
	r, err := x.Exchange(context.Background(), dnsQuestion())
	if err != nil || r.Rcode != mDNS.RcodeNameError {
		t.Fatal("NXDOMAIN changed")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err = x.Exchange(ctx, dnsQuestion()); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if b.calls.Load() != 0 {
		t.Fatal("valid negative answer or caller cancellation triggered LTE")
	}
}
func TestDNSRecoveryOnlyOneProbeAndStaleFailureIgnored(t *testing.T) {
	started, finish := make(chan struct{}), make(chan struct{})
	x, a, b := testDNSFailover(t, 0, func(ctx context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
		close(started)
		<-finish
		return nil, errors.New("old failure")
	}, dnsAnswer)
	x.mu.Lock()
	x.states[0].retryAt = time.Now().Add(-time.Second)
	x.mu.Unlock()
	done := make(chan struct{})
	go func() { x.Exchange(context.Background(), dnsQuestion()); close(done) }()
	<-started
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := x.Exchange(context.Background(), dnsQuestion()); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if a.calls.Load() != 1 || b.calls.Load() != 20 {
		t.Fatal("recovery launched concurrent Wi-Fi probes")
	}
	x.UpdateNetworkAvailability(0, false)
	x.UpdateNetworkAvailability(0, true)
	close(finish)
	<-done
	x.mu.Lock()
	defer x.mu.Unlock()
	if !x.states[0].retryAt.IsZero() {
		t.Fatal("old network failure poisoned recovered Wi-Fi")
	}
}
func TestDNSUnavailableSlotsAndClose(t *testing.T) {
	x, a, b := testDNSFailover(t, 0, dnsAnswer, dnsAnswer)
	x.UpdateNetworkAvailability(0, false)
	x.UpdateNetworkAvailability(1, false)
	if response, err := x.Exchange(context.Background(), dnsQuestion()); err != nil || response.Rcode != mDNS.RcodeServerFailure {
		t.Fatal("unavailable networks must report SERVFAIL without making the client time out")
	}
	x.Close()
	x.UpdateNetworkAvailability(0, true)
	if _, err := x.Exchange(context.Background(), dnsQuestion()); err == nil {
		t.Fatal("closed transport accepted")
	}
	if a.calls.Load()+b.calls.Load() != 0 {
		t.Fatal("unavailable slot dialed")
	}
}
