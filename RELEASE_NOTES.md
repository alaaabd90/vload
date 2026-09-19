## v1.4.33 candidate ? publication pending validation

Changes since v1.4.25:

- Allow usable mux sessions to serve requests while another session connects. Cancel queued callers and in-flight session creation promptly during pool reset.
- Synchronize SOCKS UDP peer access to eliminate a data race exercised by real HTTP/3 traffic.
- Preserve TCP_NODELAY after TLS fragmentation instead of unexpectedly enabling Nagle buffering. Reality handshakes do not use this fragmentation wrapper.
- Race real DNS queries over available VPN-bound network slots, accept the first usable answer, and cancel the losing request. FakeDNS mappings stay local to the VPN; ordinary FakeDNS browsing still sends the hostname to the selected proxy server for resolution. This does not race the VPS resolver for every browsing connection.
- Clean up tethering probe processes, streams, executors and network callbacks on timeout or interruption, and guard unsupported Android APIs.
- Register supported VLESS, Hysteria 2 and TUIC import links; normalize transport names independently of the device language.

FakeDNS remains enabled by default, Resolve Destination remains disabled, and QUIC is not blocked. Existing DNS bootstrap behavior for proxy-server hostnames is unchanged. No VPS or panel settings were modified.

Validation completed: race-enabled core, FakeDNS, DNS failure/cancellation, VLESS, mux, TCP Fast Open, HTTP/3, simulated UDP packet loss, failover and socket-protection tests; local Android emulator startup, VPN connection and FakeIP HTTPS; Android APK assembly and signature verification. The HTTPS DNS tests construct actual transports directly to avoid unrelated host-interface discovery resets.

Publication gate: the requested 75-minute Google, Instagram Reels and YouTube Shorts phone UI tests are incomplete. Automated phone Chrome launch was rejected by tool policy. Transport-only measurements are separate evidence and do not validate browser rendering or video playback smoothness. Do not publish this candidate as fully validated based on those measurements.

Known limits: whole-app lint remains nonzero, primarily hidden API, dependency, style and compatibility-analysis findings. Proxy-side DNS-over-HTTPS rejection (such as HTTP 403/429), server congestion and weak cellular coverage remain external constraints. These changes do not promise zero latency or eliminate every possible defect.
