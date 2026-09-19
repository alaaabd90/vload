package libcore

import (
	"context"
	"errors"
	mDNS "github.com/miekg/dns"
	"testing"
	"time"
)

func TestParallelDNSDoesNotWaitForBlackholedPath(t *testing.T) {
	started, canceled := make(chan struct{}), make(chan struct{})
	x, _, _ := testDNSFailover(t, 0, func(ctx context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
		close(started)
		<-ctx.Done()
		close(canceled)
		return nil, ctx.Err()
	}, func(ctx context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
		select {
		case <-started:
			return dnsAnswer(ctx, m)
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	})
	x.parallel = true
	x.attemptTimeout = time.Minute
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	r, err := x.Exchange(ctx, dnsQuestion())
	if err != nil || r.Rcode != mDNS.RcodeSuccess {
		t.Fatalf("fast answer blocked: %v", err)
	}
	select {
	case <-canceled:
	case <-time.After(time.Second):
		t.Fatal("losing DNS request leaked")
	}
}

func TestParallelDNSFailureCannotBeatUsableAnswer(t *testing.T) {
	for _, code := range []int{mDNS.RcodeServerFailure, mDNS.RcodeRefused} {
		x, _, _ := testDNSFailover(t, 0, func(_ context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
			r := new(mDNS.Msg).SetReply(m)
			r.Rcode = code
			return r, nil
		}, func(ctx context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
			time.Sleep(5 * time.Millisecond)
			return dnsAnswer(ctx, m)
		})
		x.parallel = true
		r, err := x.Exchange(context.Background(), dnsQuestion())
		if err != nil || r.Rcode != mDNS.RcodeSuccess {
			t.Fatal("failed response beat usable answer")
		}
	}
}

func TestParallelDNSUnavailablePathAndCallerCancellation(t *testing.T) {
	x, a, b := testDNSFailover(t, 0, dnsBlackhole, dnsAnswer)
	x.parallel = true
	x.UpdateNetworkAvailability(0, false)
	if r, err := x.Exchange(context.Background(), dnsQuestion()); err != nil || r.Rcode != 0 {
		t.Fatal(err)
	}
	if a.calls.Load() != 0 || b.calls.Load() != 1 {
		t.Fatal("unavailable network queried")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := x.Exchange(ctx, dnsQuestion()); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	if a.calls.Load() != 0 || b.calls.Load() != 1 {
		t.Fatal("canceled request started new work")
	}
}

func TestParallelDNSPreservesNegativeAnswer(t *testing.T) {
	x, _, _ := testDNSFailover(t, 0, func(_ context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
		r := new(mDNS.Msg).SetReply(m)
		r.Rcode = mDNS.RcodeNameError
		return r, nil
	}, dnsBlackhole)
	x.parallel = true
	r, err := x.Exchange(context.Background(), dnsQuestion())
	if err != nil || r.Rcode != mDNS.RcodeNameError {
		t.Fatal("negative answer was changed")
	}
}

func TestParallelDNSDoesNotDuplicateUpdate(t *testing.T) {
	x, a, b := testDNSFailover(t, 0, dnsAnswer, dnsAnswer)
	x.parallel = true
	m := dnsQuestion()
	m.Opcode = mDNS.OpcodeUpdate
	if _, err := x.Exchange(context.Background(), m); err != nil {
		t.Fatal(err)
	}
	if a.calls.Load()+b.calls.Load() != 1 {
		t.Fatal("DNS update duplicated")
	}
}
