## v1.4.37 local candidate ? preserve backup connection attempts

- Correct an inverted load-balancer policy: eight consecutive lost connection races previously disabled the backup attempt for 30 seconds while continuing to select the slow primary. The phone log records this policy immediately before a YouTube connection stalled for 10.24 seconds.
- Repeatedly slow members are now temporarily less preferred when another healthy member is available. A slow dial always retains its 250 ms backup attempt in load-balance mode, even when all members have slow history. Strict-priority groups retain their configured behavior.
- Preserve FakeDNS, disabled real-DNS answer caching, Resolve Destination off, mux and QUIC. No server settings changed.

Two regression tests reproduced the old timeout with a healthy backup and now pass. The full race-enabled native suite passes. Separate established-connection mux write timeouts under load are not proven resolved; no timeout values were changed and no established application data is replayed.

Phone validation: installed version 1.4.37 (code 270). All 12 Google searches completed (median first response 341 ms, maximum 1.500 s). Google and YouTube TCP/HTTP3 checks returned 200. A concurrent four-request check was stopped after Instagram timed out at 8 seconds on an already-open LTE mux stream; the other three requests succeeded, and an isolated Instagram retry returned 200 in 451 ms. Five additional inbound TUN handshake resets occurred in 0?3 ms. The setup-failover fix is verified by regression tests, but established-stream stalls remain unresolved and no stable public release is claimed.

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
