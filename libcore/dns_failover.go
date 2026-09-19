package libcore

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing/common/json/badoption"
	"github.com/sagernet/sing/service"
)

// Members retain Android slot order. Preferred changes DNS preference only;
// reordering members would send network-loss notifications to the wrong radio.
type slotDNSOptions struct {
	Servers        []string           `json:"servers"`
	Preferred      int                `json:"preferred,omitempty"`
	Parallel       bool               `json:"parallel,omitempty"`
	AttemptTimeout badoption.Duration `json:"attempt_timeout,omitempty"`
	RetryInterval  badoption.Duration `json:"retry_interval,omitempty"`
}

type slotDNSState struct {
	available  bool
	generation uint64
	retryAt    time.Time
	probing    bool
	ctx        context.Context
	cancel     context.CancelFunc
}

type slotDNSTransport struct {
	dns.TransportAdapter
	manager        adapter.DNSTransportManager
	logger         log.ContextLogger
	members        []adapter.DNSTransport
	preferred      int
	parallel       bool
	attemptTimeout time.Duration
	retryInterval  time.Duration
	mu             sync.Mutex
	states         []slotDNSState
	closed         bool
}

func newSlotDNSTransport(ctx context.Context, logger log.ContextLogger, tag string, options slotDNSOptions) (adapter.DNSTransport, error) {
	if len(options.Servers) != 2 || options.Preferred < 0 || options.Preferred > 1 {
		return nil, errors.New("slot DNS requires two servers and preferred slot 0 or 1")
	}
	if options.Servers[0] == options.Servers[1] || options.Servers[0] == tag || options.Servers[1] == tag {
		return nil, errors.New("slot DNS requires distinct child servers")
	}
	t := &slotDNSTransport{
		TransportAdapter: dns.NewTransportAdapter("vload_dns", tag, options.Servers),
		manager:          service.FromContext[adapter.DNSTransportManager](ctx),
		logger:           logger,
		preferred:        options.Preferred, attemptTimeout: time.Duration(options.AttemptTimeout),
		parallel:      options.Parallel,
		retryInterval: time.Duration(options.RetryInterval), states: make([]slotDNSState, 2),
	}
	if t.attemptTimeout == 0 {
		t.attemptTimeout = 1500 * time.Millisecond
	}
	if t.retryInterval == 0 {
		t.retryInterval = 10 * time.Second
	}
	if t.attemptTimeout < 0 || t.retryInterval < 0 {
		return nil, errors.New("slot DNS timeouts must be positive")
	}
	for i := range t.states {
		t.states[i].available = true
		t.states[i].ctx, t.states[i].cancel = context.WithCancel(context.Background())
	}
	return t, nil
}

func (t *slotDNSTransport) Start(stage adapter.StartStage) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	if t.manager == nil {
		return errors.New("missing DNS transport manager")
	}
	for _, tag := range t.Dependencies() {
		member, ok := t.manager.Transport(tag)
		if !ok {
			return fmt.Errorf("DNS member %s not found", tag)
		}
		if member.Type() == "fakeip" || member.Type() == "vload_dns" {
			return errors.New("slot DNS members must be real DNS transports")
		}
		t.members = append(t.members, member)
	}
	return nil
}

// A loss cancels queries already using that radio, so failover does not wait
// for a pooled DoH socket's timeout. Recovery also invalidates stale outcomes.
func (t *slotDNSTransport) UpdateNetworkAvailability(slot int, available bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed || slot < 0 || slot >= len(t.states) {
		return
	}
	s := &t.states[slot]
	if s.available == available {
		return
	}
	s.cancel()
	s.generation++
	s.available, s.probing, s.retryAt = available, false, time.Time{}
	s.ctx, s.cancel = context.WithCancel(context.Background())
	if !available {
		s.cancel()
	}
}

func (t *slotDNSTransport) Reset() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return
	}
	for i := range t.states {
		s := &t.states[i]
		s.cancel()
		s.generation++
		s.probing, s.retryAt = false, time.Time{}
		s.ctx, s.cancel = context.WithCancel(context.Background())
		if !s.available {
			s.cancel()
		}
	}
}

func (t *slotDNSTransport) Close() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.closed = true
	for i := range t.states {
		t.states[i].cancel()
	}
	return nil
}

func (t *slotDNSTransport) begin(slot int) (context.Context, uint64, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	s := &t.states[slot]
	if t.closed || !s.available || s.probing || time.Now().Before(s.retryAt) {
		return nil, 0, false
	}
	if !s.retryAt.IsZero() {
		s.probing = true
	}
	return s.ctx, s.generation, true
}

func (t *slotDNSTransport) finish(slot int, generation uint64, failed, canceled bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	s := &t.states[slot]
	if t.closed || s.generation != generation {
		return
	}
	s.probing = false
	if canceled {
		return
	}
	if failed {
		s.retryAt = time.Now().Add(t.retryInterval)
	} else {
		s.retryAt = time.Time{}
	}
}

func (t *slotDNSTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	if message == nil || len(message.Question) != 1 {
		return nil, errors.New("invalid DNS question")
	}
	t.mu.Lock()
	closed := t.closed
	t.mu.Unlock()
	if closed {
		return nil, errors.New("slot DNS transport closed")
	}
	if t.parallel && message.Opcode == mDNS.OpcodeQuery {
		return t.exchangeParallel(ctx, message)
	}
	var failures []error
	for offset := 0; offset < len(t.states); offset++ {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		index := (t.preferred + offset) % len(t.states)
		networkCtx, generation, ok := t.begin(index)
		if !ok {
			continue
		}
		t.logger.DebugContext(ctx, "DNS query using network slot ", index)
		attempt, cancel := context.WithTimeout(ctx, t.attemptTimeout)
		stop := context.AfterFunc(networkCtx, cancel)
		response, err := t.members[index].Exchange(attempt, message.Copy())
		stop()
		cancel()
		if err == nil && response == nil {
			err = errors.New("DNS server returned no response")
		}
		t.finish(index, generation, err != nil, ctx.Err() != nil)
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if err == nil {
			// NXDOMAIN and other valid DNS responses are answers, not proof
			// of a broken network. Do not bypass DNS policy via another radio.
			return response, nil
		}
		failures = append(failures, fmt.Errorf("DNS slot %d: %w", index, err))
		t.logger.DebugContext(ctx, "DNS network slot ", index, " failed: ", err)
		// Never replay DNS UPDATE or another potentially mutating operation.
		if message.Opcode != mDNS.OpcodeQuery {
			break
		}
	}
	if len(failures) == 0 {
		t.logger.DebugContext(ctx, "DNS network slots unavailable or cooling down")
	} else {
		t.logger.ErrorContext(ctx, "all DNS network attempts failed: ", errors.Join(failures...))
	}
	// Give the requesting app a real failure response. Returning only a Go
	// error makes the hijacked UDP path drop the query and adds the client's
	// own DNS timeout after both network attempts have already failed.
	return dns.FixedResponseStatus(message, mDNS.RcodeServerFailure), nil
}

// Race only DNS queries. Each child remains bound to its own VPN path; the
// synthetic FakeIP mapping and application connections are never duplicated.
func (t *slotDNSTransport) exchangeParallel(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	raceCtx, cancelRace := context.WithCancel(ctx)
	defer cancelRace()
	type result struct {
		response *mDNS.Msg
		err      error
	}
	results := make(chan result, len(t.states))
	pending := 0
	for index := range t.states {
		networkCtx, generation, ok := t.begin(index)
		if !ok {
			continue
		}
		pending++
		go func(index int, networkCtx context.Context, generation uint64) {
			attempt, cancel := context.WithTimeout(raceCtx, t.attemptTimeout)
			stop := context.AfterFunc(networkCtx, cancel)
			t.logger.DebugContext(ctx, "parallel DNS query using network slot ", index)
			response, err := t.members[index].Exchange(attempt, message.Copy())
			stop()
			cancel()
			if err == nil && response == nil {
				err = errors.New("DNS server returned no response")
			}
			// A canceled losing query says nothing about that network's health.
			t.finish(index, generation, err != nil, raceCtx.Err() != nil)
			results <- result{response, err}
		}(index, networkCtx, generation)
	}
	var fallback *mDNS.Msg
	for ; pending > 0; pending-- {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case r := <-results:
			if r.err != nil {
				continue
			}
			// NXDOMAIN is a valid negative answer. A fast SERVFAIL/REFUSED
			// must not beat a usable answer still arriving on the other path.
			if r.response.Rcode == mDNS.RcodeSuccess || r.response.Rcode == mDNS.RcodeNameError {
				return r.response, nil
			}
			fallback = r.response
		}
	}
	if fallback != nil {
		return fallback, nil
	}
	return dns.FixedResponseStatus(message, mDNS.RcodeServerFailure), nil
}

func (t *slotDNSTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(*mDNS.Msg, error)) {
	go func() { callback(t.Exchange(ctx, message)) }()
}
