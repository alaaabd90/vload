# vload 1.4.42 security and activation

Owner authenticator is maintained and released separately in an owner-controlled private repository.

## Install

- Most modern phones, including your Honor: vload-1.4.42-arm64-v8a.apk.
- Owner phone only: vload-authenticator-1.0.0.apk (Android 8+ and a secure screen lock required).
- Install vload as an update, without uninstalling or clearing data. It is signed with your existing release certificate. Android 6+ is now required for secure Keystore support.
- Existing installations require activation once when first moving to this security build. After activation, updates signed with the same certificate preserve activation and profiles. Keep the pinned owner public key unchanged in future builds.

## Set up your owner phone once

1. Set a PIN, password, or pattern screen lock on your owner phone.
2. Install the authenticator APK.
3. The private provisioning files are under the owner-controlled offline backup directory, OUTSIDE the repository and this shareable APK folder. Read owner-password.txt privately on your PC. Transfer owner-provisioning.json only to your owner phone.
4. Enter that provisioning password in the authenticator, then choose Import owner provisioning file and select the JSON file. Alternatively, use Import setup text and paste the encrypted JSON contents after entering the password.
5. The app imports the signing key into Android Keystore and requires screen-lock authentication to sign. Delete the transferred provisioning JSON from the phone after successful setup. Keep a secure offline PC backup. Do not distribute the provisioning file or password. Together they can provision another owner phone. The authenticator APK alone cannot issue codes.

## Activate a customer phone

1. Customer opens vload, copies its VLR1 phone request, and sends it to you.
2. Paste the request into the authenticator. Tap Unlock and generate and confirm your owner phone's screen lock.
3. Copy the displayed activation code and send it to that customer. The code refreshes each 30-second time step. vload allows a one-step clock tolerance; keep both phone clocks correct. Signing is allowed for 60 seconds after screen-lock confirmation, then unlock again.
4. Customer pastes the code into vload and taps Activate. Normal updates never ask again on this installation. Another phone, uninstall, app-data clearing, or loss of the Keystore identity needs a new activation.

These are long, digitally signed, phone-specific codes, not six-digit TOTP codes. Offline six-digit verification with a shared embedded secret would weaken your owner-only control. The owner private key is in neither APK.

## Secure locked profiles

Recipient opens About and taps Profile recipient key. The sender uses that VLP3 public key in Export locked to device. The new file uses a random AES-256-GCM key wrapped by the recipient's RSA-2048-OAEP key (SHA-256, MGF1-SHA1 for Android compatibility). The whole header is authenticated. The recipient private key is generated in Android Keystore and is not exportable through the app.

Two explicit export choices are supported: Secure lock by recipient key (V3), and Lock by HWID (legacy V2). V2 imports are accepted for compatibility at the user's request; V1 still requires re-export because its identifier format changed. Legacy HWID locking survives ordinary reinstall with the same signing certificate and Android user, but it does not provide strong confidentiality: the file header exposes the information needed to derive its key. Their encryption was insecure because the key could be derived from the HWID included in the file. Already imported/saved profiles are preserved and migrated to encrypted storage. Existing copies of old files, backups, and logs cannot be made secure retroactively. Keep any required original profiles on the sender's phone. Reinstalling the recipient loses its device key and requires new locked exports.

Room profile/subscription payloads are encrypted with a separate Keystore AES key. Migration preserves fields and uses SQLite secure_delete. Names/IDs/other metadata are not a full-database encryption scheme. Normal user-created unlocked exports remain portable. Cloud/device transfer backups are disabled; use deliberate unlocked exports for your own portable source profiles. Do not downgrade to old versions after encrypted-storage migration.

Locked profiles cannot be opened in the editor or exported through profile, group, chain, or load-balance export paths. Generated native/plugin configurations are no longer written to app logs. Screenshot/recents capture is blocked on sensitive app screens. ADS and Document navigation entries and their handlers are removed.

## Protection limits

No local VPN app can make used credentials or executable code impossible to extract. A compromised authorized runtime can observe plaintext or invoke its Keystore keys. Hardware backing depends on device support. Root, instrumentation, or patched code can bypass client-side guards. APK files and the existing public source can still be copied; the unchanged official APK requires a separate activation on another installation. Stronger tamper/revocation enforcement would require an online service and server-side credentials/attestation. Offline expiry depends on the device clock.

Release R8 obfuscation is enabled, with keep rules for serialization/reflection/JNI boundaries and existing upstream namespaces. Certificate checks pin your current release signing certificate. These increase effort; they do not make the public source secret or prevent all clones. Existing open-source notices remain intact.

## Validation

- Four vload release ABIs plus the owner APK built and passed APK signature and ZIP integrity checks; see verification.json for SHA-256 hashes.
- Debug Android suite: 21 tests reported OK (physical-phone-only test skipped by its explicit argument requirement). Includes secure export round trips, tampering/wrong recipient, encrypted Room storage and legacy migration, export restrictions, SNISpoof, SIM-card persistence/UI and generated load-balance policies.
- Debug activation survived an APK update.
- Actual obfuscated release UI on Android 16 emulator: first-run gate, wrong-phone rejection, expired-code rejection, valid activation, and persistence after reinstall-as-update passed.
- Actual owner release on Android 11 phone emulator: encrypted file import, screen-lock confirmation, signature verified independently, owner-generated code accepted by vload on the other emulator, and refresh across a 30-second boundary passed.
- Test owner key, imported setup file, and temporary PIN were removed from the phone emulator afterward.
- Full release instrumentation could not run because the separately shrunk target/test desugared libraries collide (j$.util.concurrent.ThreadLocalRandom VerifyError). The release APK itself opens and passes the direct UI checks above; the debug regression result must not be represented as full release instrumentation coverage.
- Physical Honor phone: owner activation and update persistence passed; all five original profiles preserved; all stored payloads encrypted; recipient key hardware-backed; imported locked vpn.test matches the original serialized settings. Three built-in HTTP checks after VPN restarts passed. No new physical SIM-switch trace was recorded. Load-balancing/mux/native source files are unchanged. The prior intermittent h2mux test issue remains unresolved, as documented in the existing handoff.

## Pantegnos review

Pantegnos is format-specific, not a universal decryption oracle. Its HAT implementation uses a fixed key, and its Happ crypt5 implementation bundles recovered RSA private keys. Its BPF parser also handles archive/config data. No Pantegnos code or keys were incorporated in vload.

- https://github.com/FrontierTM/Pantegnos/blob/main/internal/modules/impl/hat.go
- https://github.com/FrontierTM/Pantegnos/blob/main/internal/modules/impl/happ_crypt5.go
- https://github.com/FrontierTM/Pantegnos/blob/main/internal/modules/impl/bpf.go
- https://developer.android.com/privacy-and-security/keystore
