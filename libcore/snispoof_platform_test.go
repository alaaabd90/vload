package libcore

import (
	"errors"
	"testing"
)

type profileTestPlatform struct {
	BoxPlatformInterface
	fd  int32
	err error
}

func (p *profileTestPlatform) AutoDetectInterfaceControl(fd int32) error { p.fd = fd; return p.err }
func TestProfileTestSocketBinding(t *testing.T) {
	denied := errors.New("physical network unavailable")
	platform := &profileTestPlatform{err: denied}
	wrapper := &boxPlatformInterfaceWrapper{testPlatform: platform}
	if err := wrapper.AutoDetectInterfaceControl(42); !errors.Is(err, denied) {
		t.Fatalf("binding failure must stop dial: %v", err)
	}
	if platform.fd != 42 {
		t.Fatal("socket not passed to per-instance callback")
	}
	platform.err = nil
	if err := wrapper.AutoDetectInterfaceControl(43); err != nil {
		t.Fatal(err)
	}
	if boxPlatformInterfaceInstance.(*boxPlatformInterfaceWrapper).testPlatform != nil {
		t.Fatal("test callback changed production singleton")
	}
}
