## v1.4.39 local candidate - idle preconnection accounting

- Do not treat successfully opened connections that have not sent a request as pending responses. Accumulated idle preconnections could previously displace browsing from a responsive network onto a slower one.
- Continue counting connection attempts and real first-response waits. Synchronize ready, write, response and close transitions so cancellation and concurrent callbacks cannot leak or double-release pending counts.
- Preserve FakeDNS, disabled DNS caching, Resolve Destination off, mux, QUIC and server settings.

Validation: the idle-preconnection regression failed before the fix and passes after. Full race-enabled native suites and four-ABI Android builds passed; signatures and packaged native libraries were verified. The local phone candidate completed 12/12 Google requests (311 ms median first response, 345 ms maximum) and a four-site concurrent request check. Connection reuse checks cover HTTP/2 and HTTP/3 across idle intervals.

The saved 1.4.38 logs do not prove this defect caused every reported slowdown. The candidate has not completed a one-hour browser/video endurance test and is not published on GitHub.

## v1.4.38 - browsing responsiveness under peer load

- Separate web/DNS mux traffic on ports 53, 80, 443 and 853 from other ports. With a configured connection limit of at least two, reserve one quarter of that limit, up to four connections, for web/DNS traffic while retaining the same total maximum. Single-connection, stream-limit-only and Brutal configurations retain their original behavior.
- Keep web/DNS latency measurements, pending attempts and slow-path history separate from peer traffic, so a busy peer pool does not unnecessarily push browsing onto a slower network.
- Smooth latency measurements to avoid switching networks because of one delayed response, while still reacting to sustained slowness.
- Preserve FakeDNS, disabled real-DNS caching, Resolve Destination off, TFO, mux protocols and QUIC. No server settings changed.

Validation: regression tests reproduced the original blocking and selection problems and pass with these fixes. For yamux, smux and h2mux, a stress test completed 256 peer transfers alongside 32 web requests within a 24-transport budget. The full race-enabled native suite and four-ABI Android builds pass. Two final phone samples completed 24/24 Google requests; the latest sample had a 340 ms median first response and 585 ms maximum. Concurrent four-site checks and Google/YouTube TCP and HTTP/3 checks also passed.

Port-based separation cannot distinguish bulk HTTPS downloads from browsing on the same ports. Reserving capacity can trade peak parallel throughput for isolation; real-phone peak bulk throughput has not been benchmarked. Short samples do not guarantee unlimited traffic, zero latency or freedom from network/server stalls. Server-side DNS rejections, NXDOMAIN responses and client-reset errors remain separate issues.

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
