# SNISpoof implementation and validation

Baseline: public v1.4.39, commit d9cbb446604e3129aebc0ae9109fd1cfd90d5753.
Release version: 1.4.40, Android versionCode 280.
Developed and validated against the release baseline above.

## Profile controls

Edit a TLS VLESS, VMess, Trojan or HTTPS profile and enable SNISpoof. All
controls appear in that profile editor; there are no new global settings.
The native TLS dialer handles the connection internally, without a separate
app, localhost port, root, or bootloader changes.

Newly enabled profiles default to fake api.twitter.com, TTL 6 with automatic
lower-TTL retry, 10 ms fake delay, automatic fragmentation (128 bytes, 10 ms),
and required ECH off. Each profile's own server remains the primary address;
the tested user's IP is not hardcoded. Existing saved options are preserved.

Default-update verification: nine emulator tests passed (the explicit physical
phone test was skipped there). On the Honor phone, the saved test profile passed
four file/import/database round trips: normal and HWID-locked, each enabled and
disabled. Exact SNISpoof JSON and re-exported links matched; temporary copies
were removed and the source profile was preserved. The updated release passed
21/24 LTE HTTPS requests; three transient failures mean this run was not fully
reliable. Wi-Fi remained disabled.

- Send fake ClientHello: rootless Linux/Android zero-copy TCP decoy injection.
- Fake SNI hostname: defaults to api.twitter.com; the real SNI, certificate
  identity, WebSocket Host/path and credentials are unchanged.
- Fake TTL (1-32), optional bounded lower-TTL retry, and transmission delay.
- Test fake SNI and TTL: compares the configured TTL and lower values on fresh
  connections to the profile address, retaining required ECH. Offers the
  highest successful tested TTL, rather than claiming the fastest TTL reaches DPI.
- Fragmentation: automatic, SNI split, TLS record split, small writes or none;
  size and delay controls. Automatic mode tries SNI, record and chunk methods.
- Require accepted ECH: fails if the real server does not accept ECH. No
  plaintext fallback. An optional PEM/base64 ECH configuration can be supplied.
- Empty ECH config uses bounded HTTPS DNS queries through the same protected
  physical-network dialer. Public ECH keys are cached according to TTL, up to
  one hour; ordinary real-DNS answer caching is unchanged.
- Explicit IPs/CIDRs, optional bounded Cloudflare discovery, candidate limits,
  per-attempt/total time budgets, failed-path cooldown and diagnostics.
- Method tests and IP discovery verify an HTTPS request through the profile.
  Applying a result is optional. Stop prevents further bounded test requests.

SNISpoof is limited to verified ordinary TLS over TCP, including TCP, WebSocket,
HTTP, HTTPUpgrade and gRPC transports. Reality, QUIC and custom
JSON overrides are rejected when enabled. Certificate verification is forced
on; the legacy Allow insecure value is preserved and restored when disabled.
Fake mode needs an exposed direct
TCP socket and kernel vmsplice/splice support; it fails rather than silently
omitting injection when unsupported. Other profile editors are unchanged.
Cloudflare discovery is appropriate only for Cloudflare-hosted profiles.

## What the decoy does and does not prove

An independently constructed, randomized ClientHello carries the fake name.
A short TTL limits its reach. Mapped pages are transferred into the TCP send
queue with vmsplice/splice, then replaced with the original TLS bytes before
retransmission; the original socket TTL is restored. The remote server must
receive the original bytes, and its certificate is still checked normally.

The TTL must expire AFTER an observer but BEFORE the server. A successful VPN
request alone cannot prove that a specific carrier DPI saw the decoy, or that
it relied on its hostname. Retransmission, ECH and fragmentation can also
contribute. This does not make a VPN undetectable, and routes/DPI policies can
change. A TTL that reaches the server can break TLS. Automatic retry uses
fresh connections and never silently disables an enabled fake/ECH requirement.

This mechanism follows the technique published by hufrea/ByeDPI, MIT license,
reference commit ba532298de7b28cfe854aea83d061369d13ca290:
https://github.com/hufrea/byedpi/blob/ba532298de7b28cfe854aea83d061369d13ca290/desync.c
The license is included in the native source and APK assets/licenses/byedpi.txt.
No SNISPF binary or separate proxy service is embedded.

## Disabled behavior and persistence

With SNISpoof off, the original SNI Fragmentation, ECH Enable and ECH Config
controls remain at their original locations and editable. Their stored values
are preserved across SNISpoof toggles. The original v1.4.39 TLS builder/dialer
paths are used; all new policy options are inactive and hidden.

The master switch and versioned JSON envelope are appended to the standard
profile binary format (version 7). Old version-6 records default off. Standard
and universal links, plain .vload files, and HWID-locked .vload files retain the
new fields. Unknown JSON keys survive editor saves and sharing. Existing device
locking and edit restrictions are unchanged.

## Verification

- Native race suite passes, including real TLS/ECH, transports/mux, cancellation,
  fragmentation, candidate limits, cooldown/network replacement, fake hello
  generation and failure without an exposed socket.
- Eight Android emulator tests pass: controls, legacy switch/value restoration,
  all four legacy fragmentation/ECH combinations, old binary migration, unknown
  fields and normal/locked import-export with the new fake fields included.
- Isolated routed Linux capture: unprivileged uid 1000 client; observer sees
  api.twitter.com with TTL 1; the decoy expires at the router. The server sees
  only c.fnacdn.top. Certificate-verified TLS and echo pass in all four modes
  (none, SNI, record, chunks). Captures and parsed proof are in the workspace.
- Physical Honor BKQ-N49: fake SNI succeeds without root. On Wi-Fi, TTL 8 failed;
  TTLs 6, 4 and 2 passed with accepted ECH. 24/24 HTTPS requests and three VPN
  restarts passed with the fake policy enabled.
- LTE, Wi-Fi disabled: the original vpn.2 failed repeatedly. On the same primary
  address, fake-disabled tests failed for none/SNI/record/chunks and both ECH
  alternatives. Fake api.twitter.com WITHOUT ECH passed at TTLs 6, 4 and 2;
  two full VPN runs passed 48/48 HTTPS requests across three sites.
- Requiring ECH with fake mode on the original IP timed out in the final LTE
  comparison. The final working LTE profile therefore has ECH off, fake SNI
  enabled at TTL 6 with lower-TTL retry, and the original IP prioritized.
- During network handover an in-flight request failed; a subsequent request
  recovered. This is not a claim of uninterrupted sessions during handover.

Profile editor tests use per-instance physical-network socket binding, so they
also work with the VPN stopped. Binding failures stop testing. The production
VPN protection path remains unchanged. Editor testing requires Android 6+.

The native patch is buildScript/lib/core/patches/sing-box-snispoof.patch, applied
after the released dependency patches. Dependency pins are unchanged. Four ABI
APKs are signed with the same certificate as v1.4.39. The tested profile was
exported to the phone's Download/vpn.2-LTE-api-twitter.vload; the original remains.
Wi-Fi was left off and the working test VPN connected at the user's request.
