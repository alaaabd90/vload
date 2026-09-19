## v1.4.35 local candidate — browsing validation incomplete

- Cancel pending attempts on a lost physical network and reconsider a second network that appears after the initial hedge timer.
- Handle a replacement network arriving before its old connection attempt finishes cancellation.
- Keep an Android network slot unavailable until its old connections and transports have been cleaned up; reject obsolete callbacks and sockets bound to a replaced network.
- Credit load-balancer recovery and capacity only after a real TCP/UDP response, rather than local mux-stream allocation.
- Reload version metadata between Gradle builds instead of retaining stale values in the daemon.

Race-enabled native regression tests and four-ABI debug builds pass. FakeDNS remains enabled, real-DNS answer caching remains disabled, and Resolve Destination and QUIC settings are unchanged.

The first 1.4.35 candidate passed 40 HTTP probes spanning a Wi-Fi interruption and 96 FakeIP HTTPS requests. The user nevertheless reported worse browsing, so the load test was stopped. Those results do not establish smooth browsing or streaming. The subsequent response-based health correction is covered by TCP/UDP regression tests, but sustained phone browsing validation remains incomplete. This is not a public stable release or a claim that all latency is fixed.

## v1.4.34 local candidate

- Disable real-DNS answer caching in generated configurations. Preserve FakeDNS address mappings across reconnects.
- Replace mux sessions whose remote DNS router has closed after a server reload.
- Avoid blocking an otherwise usable mux session while an additional tunnel connects.
- Fix concurrent h2mux response setup/read/close and late response-body cleanup.

Race-enabled native regression tests and four-ABI Android builds passed. The phone completed 200 single-profile FakeIP HTTPS requests and 120 concurrent page/download requests across both profiles. A load-balance test still had one Google TLS failure, and a single-profile Google request took 7.85 seconds. Further investigation is required; this candidate is not a claim of fully stable browsing. No UI/video endurance or weak-LTE failover validation was completed.
