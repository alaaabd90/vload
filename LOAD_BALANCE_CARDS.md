# Per-card VPN profiles

Based on public v1.4.40 (`6e9f5fc`). Local candidate:
`1.4.41-simcards-local`, versionCode 285.

Edit a Load Balance profile, choose Network A or B, select SIM, then
**Follow phone’s data SIM**. Use **Add Card name** to select an active SIM or
eSIM and assign its VPN profile. Repeat for other cards. Activate an eSIM in
Android before adding it. Saved inactive cards remain in the editor.

The new mode requires Android 11+ and Phone permission. Android's actual
active-data subscription determines the profile, including opportunistic data
switches. The app does not change the phone's data-SIM selection or activate
inactive eSIMs. Names are display labels; subscription IDs identify the cards.
Reinstalled eSIMs or another phone may have different IDs and need reassignment.
An unmapped active SIM leaves that network slot unavailable; the other slot can
continue serving traffic. This mode follows one active data SIM per slot; it
does not promise simultaneous data connections on every SIM in a dual-SIM phone.

Existing fixed-SIM/Wi-Fi profiles retain their configuration and behavior.
LoadBalanceBean version 1 appends both card lists; version 0 reads with empty
lists and the original fixed subscription/profile/weight fields intact. Card
lists remain stored when switching a slot back to Wi-Fi or a fixed SIM.

Only opted-in slots get a selector below the existing weighted groups. The
weighted algorithms, weights, DNS priority, QUIC priority and routing policy
are unchanged. A data-SIM change first makes the slot unavailable, unregisters
its old network request, drains its connections/transports, selects the mapped
profile and then restores availability once Android supplies that SIM network.
Generation checks reject stale callbacks. The other slot is not restarted.

Validation:
- Full native race suite passed, including a new card-switch test that checks
  traffic/DNS/QUIC follow the new card and existing Wi-Fi connections stay open.
- Android emulator: 12 tests passed; one explicit physical-phone test skipped.
  Tests cover legacy binary loading, card identity, inactive eSIM persistence,
  shared profiles without duplicate outbound tags, unchanged group modes and
  weights, and card editor visibility/preservation.
- Physical Zain/Asia switching remains pending: the phone was disconnected.

Android APIs: [active data SIM](https://developer.android.com/reference/android/telephony/SubscriptionManager#getActiveDataSubscriptionId()),
[change listener](https://developer.android.com/reference/android/telephony/TelephonyCallback.ActiveDataSubscriptionIdListener).
