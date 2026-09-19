## v1.4.34 local candidate

- Disable real-DNS answer caching in generated configurations. Preserve FakeDNS address mappings across reconnects.
- Replace mux sessions whose remote DNS router has closed after a server reload.
- Avoid blocking an otherwise usable mux session while an additional tunnel connects.
- Fix concurrent h2mux response setup/read/close and late response-body cleanup.

Race-enabled native regression tests and four-ABI Android builds passed. The phone completed 200 single-profile FakeIP HTTPS requests and 120 concurrent page/download requests across both profiles. A load-balance test still had one Google TLS failure, and a single-profile Google request took 7.85 seconds. Further investigation is required; this candidate is not a claim of fully stable browsing. No UI/video endurance or weak-LTE failover validation was completed.
