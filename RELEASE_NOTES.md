## v1.4.38 local candidate ? separate web traffic from peer load

- Give web/DNS ports (53, 80, 443, 853) separate mux transports from other ports when a connection-count limit of at least two is configured. Reserve one quarter of the configured limit, up to four connections, for this pool; retain the same total maximum. With the tested 24-connection configuration this is four web/DNS and twenty other transports. Single-connection, stream-limit-only and Brutal configurations retain their original pool behavior.
- Track web/DNS response latency, pending attempts and slow-dial history separately from peer traffic. Peer connection counts no longer determine the initial web path, and busy peer attempts no longer displace a responsive web path.
- Smooth response measurements in both directions so one destination's DNS/server delay does not immediately replace the network's latency history. Sustained slow responses still change path preference. Physical-network availability, circuit-breaker recovery and strict-priority groups remain in place.
- Preserve FakeDNS, disabled real-DNS caching, Resolve Destination off, TFO, mux protocols and QUIC. No server settings changed.

Controlled tests reproduced shared-transport blocking and peer-pressure interference before the fixes. The corrected tests pass for yamux, smux and h2mux. A stress test completed 256 peer transfers concurrently with 32 web requests, within the 24-transport budget, for each protocol. The full race-enabled native suite and four-ABI Android builds pass.

The first candidate had an eight-second Google body-download timeout and a Google TCP EOF; it was removed for comparison. After correcting the latency-outlier behavior, the revised candidate completed 12/12 Google searches (median 308 ms, maximum 1.351 s), plus successful Google/YouTube TCP and HTTP/3 checks. A four-site concurrent check completed with first responses of 180?412 ms while the phone log showed substantial peer activity. A second 12-request sample with peer activity also completed 12/12 (median 340 ms, maximum 585 ms). Short samples vary with network conditions and are not proof of universal improvement. Background NXDOMAIN and inbound client-reset errors remain; real provider DNS rejections are not fixed by pool isolation.

This is a local candidate, not a public release. Port-based separation cannot distinguish bulk HTTPS transfers from browser traffic on the same ports, and reserving transport capacity can trade peak parallel throughput for isolation. It cannot guarantee unlimited traffic, zero latency or freedom from network/server congestion.

## v1.4.37 ? browsing and network recovery fixes

- Improve load-balance selection using recent first-response latency and pending connection attempts. Idle browser connections no longer push new requests onto a slower network.
- Correct backup-dial behavior after repeated slow attempts: temporarily prefer a responsive alternative while retaining the 250 ms backup attempt. Previously, eight lost races disabled the backup for 30 seconds and could leave browsing stalled on LTE.
- Cancel obsolete network attempts during disconnects and replacements, and prevent late callbacks from changing the replacement network's health measurements.
- Recover mux sessions after a remote DNS-router shutdown and allow usable sessions to serve requests while the connection pool grows.
- Disable real-DNS answer caching while preserving FakeDNS address mappings. Resolve Destination, configured mux, and QUIC remain supported.
- Fix build-version metadata refresh so APK and bundle versions follow the release configuration.

Validation: race-enabled native regression tests and four-ABI Android builds pass. The tested phone candidate completed 12/12 Google searches (median first response 341 ms); Google and YouTube TCP/HTTP3 checks also succeeded. These are short transport checks, not browser-rendering or video-endurance guarantees. Intermittent established-mux stalls remain under investigation: a concurrent Instagram request timed out once, then succeeded on an isolated retry. This release does not promise zero latency or uninterrupted service under weak-network conditions.

## Earlier local validation records

## v1.4.36 local candidate ? response-aware network selection

- Select load-balance paths using recent first-response delay and pending connection attempts. Idle established connections no longer make a responsive path look busy.
- Prefer a measured responsive path over an unmeasured idle path; account for pending attempts before dialing completes, and release counts on failure/cancellation.
- Isolate latency measurements across network replacements, including late callbacks; expire measurements after one minute. Preserve strict-priority DNS/QUIC behavior.
- Preserve FakeDNS, disabled real-DNS caching, Resolve Destination off, mux and QUIC.

Regression tests reproduced both idle-connection and unmeasured-path selection defects before the fixes. Race-enabled native tests and four-ABI debug builds pass. Patch application against the pinned core and packaged native binary hashes were verified. The revised arm64 build is installed on the test phone (version code 265).

Short phone measurements: the earlier 1.4.35 Google search sample completed 11/12 requests (one five-second timeout; successful median first response 615 ms). The final 1.4.36 sample completed 12/12 (median 324 ms, maximum 1.403 s); single-profile requests completed 12/12 (median 414 ms, maximum 625 ms). A cold four-request burst completed without failure, with Google first response 1.005 s versus 2.455 s in the first 1.4.36 revision. Samples were sequential in time and are not controlled proof of a universal speed increase.

Direct TUN/FakeDNS checks returned local DNS answers in 2?3 ms. Eight TCP Google search responses returned 200; eight HTTP/3 responses returned Google's 302 consent redirect. Google and YouTube HTTP/3 robots checks returned 200. Remaining handshake spikes reached 1.7 s. A QUIC-only helper timed out when following the consent redirect; that helper does not reproduce browser protocol fallback. Four background TUN handshake-report errors also remain in the log and are not proven fixed by this change. This candidate is not a public stable release, a completed Chrome/video endurance test, or a guarantee of zero latency.

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
