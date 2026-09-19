## v1.4.33

Changes since v1.4.25:

- Fix VLESS Vision shutdown sending an outer TLS close record into an already-spliced stream, which could corrupt tunneled TLS traffic.
- Accept s-ui TUIC certificate-verification option aliases when importing profiles.

- Allow usable mux sessions to serve requests while another session connects. Cancel queued callers and in-flight session creation promptly during pool reset.
- Synchronize SOCKS UDP peer access to eliminate a data race exercised by real HTTP/3 traffic.
- Preserve TCP_NODELAY after TLS fragmentation instead of unexpectedly enabling Nagle buffering. Reality handshakes do not use this fragmentation wrapper.
- Race real DNS queries over available VPN-bound network slots, accept the first usable answer, and cancel the losing request. FakeDNS mappings stay local to the VPN; ordinary FakeDNS browsing still sends the hostname to the selected proxy server for resolution. This does not race the VPS resolver for every browsing connection.
- Clean up tethering probe processes, streams, executors and network callbacks on timeout or interruption, and guard unsupported Android APIs.
- Register supported VLESS, Hysteria 2 and TUIC import links; normalize transport names independently of the device language.

FakeDNS remains enabled by default, Resolve Destination remains disabled, and QUIC is not blocked. Existing DNS bootstrap behavior for proxy-server hostnames is unchanged.

Validation completed: race-enabled core, FakeDNS, DNS failure/cancellation, VLESS, mux, TCP Fast Open, HTTP/3, simulated UDP packet loss, failover and socket-protection tests; local Android emulator startup, VPN connection and FakeIP HTTPS; Android APK assembly and signature verification. The HTTPS DNS tests construct actual transports directly to avoid unrelated host-interface discovery resets.

Phone validation: the final candidate completed 200 HTTPS requests in a single profile and 200 in a load-balance profile. An earlier load-balance run had a timeout, and slow responses remain under investigation. These transport checks do not establish browser rendering or video playback smoothness. The requested extended Google, Instagram Reels and YouTube Shorts UI endurance tests were not completed. Weak-LTE failover has not been validated on the connected phone.

Release artifacts include signed APKs for all four Android ABIs and a signed Play app bundle. A signed bundle does not imply Google Play acceptance.

Known limits: whole-app lint remains nonzero, primarily hidden API, dependency, style and compatibility-analysis findings. Proxy-side DNS-over-HTTPS rejection (such as HTTP 403/429), server congestion and weak cellular coverage remain external constraints. These changes do not promise zero latency or eliminate every possible defect.
