package libcore

// Thin gomobile-visible wrapper around the localtether/ package (the local
// Shizuku tethering feature's userspace datapath, ported unchanged from
// vhost/shizzi's datapath module). Folded into libcore's own single gomobile
// bind rather than shipped as a separate AAR: two independently gomobile-bound
// AARs in one app collide on the shared go.Seq/go.Universe runtime classes and
// on libgojni.so itself, since gomobile always names its native library the
// same regardless of which packages were bound.

import "libcore/localtether"

type LocalTetherSession struct {
	inner *localtether.Session
}

// StartLocalTether attaches a userspace netstack to tunFD (already open) and
// starts terminating tethered clients' traffic in-process. mtu is the link MTU.
func StartLocalTether(tunFD int, mtu int) (*LocalTetherSession, error) {
	session, err := localtether.Start(tunFD, mtu)
	if err != nil {
		return nil, err
	}
	return &LocalTetherSession{inner: session}, nil
}

// SetNetwork pins every subsequent dial to a handle from
// Network.getNetworkHandle; 0 unbinds, which is how a session with no VPN runs.
func (s *LocalTetherSession) SetNetwork(handle int64) {
	s.inner.SetNetwork(handle)
}

// Stop tears the netstack down. It does not close the TUN fd.
func (s *LocalTetherSession) Stop() {
	s.inner.Stop()
}
