## v1.4.25

Prerelease with stability fixes based on the published v1.4.24-d release and sing-box 1.14.1.

- Preserve FakeDNS mappings across VPN restarts, flush pending cache writes on shutdown, and avoid reusing persisted addresses after interrupted sessions.
- Recover a stale FakeDNS connection from its HTTP/TLS/QUIC hostname when available, without forwarding synthetic addresses to the proxy server.
- Fix mux startup with TCP Fast Open and preserve the winning load-balance dial context until its connection closes.
- Propagate network-slot availability to browsing, DNS, and QUIC groups. Reset the affected network's pooled transports without resetting the healthy member.
- Handle delayed Android socket-protection requests and report protection/binding failures correctly.
- Guard Android 6+ battery and network-slot APIs on older Android versions. Load balancing requires Android 6 or later; selecting a specific SIM requires Android 11 or later.
- Keep Resolve Destination disabled by default. Its behavior is unchanged from v1.4.24-d.
- Route remote DNS through the proxy in both single-profile and load-balance modes, including the DNS endpoint hostname. Proxy-server hostnames may still require bootstrap DNS before a tunnel exists.

Validation includes race-enabled FakeDNS, routing, load-balance, socket-protection, and real VLESS mux/TCP Fast Open regression tests, plus the Android network callback harness. Phone checks covered FakeDNS HTTPS requests and network-slot loss/recovery. These checks do not guarantee performance on every server or mobile network.

Known limitation: a proxy server's DNS-over-HTTPS resolver can reject lookups with HTTP 403/429. Such failures still prevent browsing even when the client FakeDNS mapping succeeds. This release does not change VPS or panel settings or work around those errors by enabling Resolve Destination.

The whole-app lint check is not clean. Remaining findings include older-Android compatibility in local tethering, intentional hidden API use, and dependency/style warnings treated as errors. This prerelease is not a claim that every app feature or supported Android version has been validated.
