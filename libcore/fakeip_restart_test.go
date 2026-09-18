package libcore

import (
	"context"
	"fmt"
	"net/netip"
	"path/filepath"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/dns/transport/fakeip"
	"github.com/sagernet/sing-box/experimental/cachefile"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/service"
)

func TestFakeIPMappingSurvivesRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "fakeip-cache.db")
	open := func() (*fakeip.Store, *cachefile.CacheFile) {
		t.Helper()
		ctx := service.ContextWithDefaultRegistry(context.Background())
		cache := cachefile.New(ctx, logger.NOP(), option.CacheFileOptions{
			Enabled: true, Path: path, StoreFakeIP: true,
		})
		if err := cache.Start(adapter.StartStateInitialize); err != nil {
			t.Fatal(err)
		}
		service.MustRegister[adapter.CacheFile](ctx, cache)
		store := fakeip.NewStore(ctx, logger.NOP(), netip.MustParsePrefix("198.18.0.0/15"), netip.Prefix{})
		if err := store.Start(); err != nil {
			t.Fatal(err)
		}
		return store, cache
	}
	store, cache := open()
	address, err := store.Create("example.com", false)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	if err := cache.Close(); err != nil {
		t.Fatal(err)
	}
	store, cache = open()
	defer cache.Close()
	defer store.Close()
	if domain, ok := store.Lookup(address); !ok || domain != "example.com" {
		t.Fatalf("cached client address lost after restart: found=%v, domain=%q", ok, domain)
	}
}

func TestFakeIPCloseFlushesPendingMappingsAndRecoversCursor(t *testing.T) {
	path := filepath.Join(t.TempDir(), "fakeip-cache.db")
	open := func() *cachefile.CacheFile {
		cache := cachefile.New(context.Background(), logger.NOP(), option.CacheFileOptions{Enabled: true, Path: path, StoreFakeIP: true})
		if err := cache.Start(adapter.StartStateInitialize); err != nil {
			t.Fatal(err)
		}
		return cache
	}
	cache := open()
	prefix := netip.MustParsePrefix("198.18.0.0/15")
	first := netip.MustParseAddr("198.18.0.2")
	if err := cache.FakeIPSaveMetadata(&adapter.FakeIPMetadata{Inet4Range: prefix, Inet4Current: first}); err != nil {
		t.Fatal(err)
	}
	address := first
	for i := 0; i < 100; i++ {
		address = address.Next()
		cache.FakeIPStoreAsync(address, fmt.Sprintf("domain-%d.example", i), logger.NOP())
	}
	if err := cache.Close(); err != nil {
		t.Fatal(err)
	}
	cache = open()
	defer cache.Close()
	address = first
	for i := 0; i < 100; i++ {
		address = address.Next()
		if domain, ok := cache.FakeIPLoad(address); !ok || domain != fmt.Sprintf("domain-%d.example", i) {
			t.Fatalf("pending mapping %d lost", i)
		}
	}
	if metadata := cache.FakeIPMetadata(); metadata == nil || metadata.Inet4Current != address {
		t.Fatal("allocation cursor ignored durable mappings after its checkpoint")
	}
	if err := cache.FakeIPReset(); err != nil {
		t.Fatal(err)
	}
	if _, ok := cache.FakeIPLoad(address); ok {
		t.Fatal("IPv4-only cache reset failed without an IPv6 bucket")
	}
}

func TestFakeIPMappingSurvivesUncleanSecondSession(t *testing.T) {
	path := filepath.Join(t.TempDir(), "fakeip-cache.db")
	open := func() (*fakeip.Store, *cachefile.CacheFile) {
		ctx := service.ContextWithDefaultRegistry(context.Background())
		cache := cachefile.New(ctx, logger.NOP(), option.CacheFileOptions{Enabled: true, Path: path, StoreFakeIP: true})
		if err := cache.Start(adapter.StartStateInitialize); err != nil {
			t.Fatal(err)
		}
		service.MustRegister[adapter.CacheFile](ctx, cache)
		store := fakeip.NewStore(ctx, logger.NOP(), netip.MustParsePrefix("198.18.0.0/15"), netip.Prefix{})
		if err := store.Start(); err != nil {
			t.Fatal(err)
		}
		return store, cache
	}
	store, cache := open()
	address, err := store.Create("example.com", false)
	if err != nil {
		t.Fatal(err)
	}
	// Ensure the mapping itself is durable before simulating a later session.
	if err := cache.FakeIPStore(address, "example.com"); err != nil {
		t.Fatal(err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	if err := cache.Close(); err != nil {
		t.Fatal(err)
	}
	_, cache = open()
	// Model process loss: no Store.Close/metadata save and no new DNS queries.
	if err := cache.Close(); err != nil {
		t.Fatal(err)
	}
	store, cache = open()
	defer cache.Close()
	defer store.Close()
	if domain, ok := store.Lookup(address); !ok || domain != "example.com" {
		t.Fatal("a session without new DNS queries erased previously durable FakeIP mappings")
	}
	next, err := store.Create("another.example", false)
	if err != nil {
		t.Fatal(err)
	}
	if next == address {
		t.Fatal("restart reissued an address still cached for another domain")
	}
}
