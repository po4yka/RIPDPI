# Change: Complete AmneziaWG 3.1 behavior and verification

Task ID: `TRN-1793271658511991`

## Why

RIPDPI accepts AmneziaWG 3.1 profiles and implements their packet envelope, but some accepted settings do not control the runtime. Four timer fields are discarded after validation; rekey timing is partial; outgoing keepalives bypass content padding; DisableCookies does not control the backend load path. The receive classifier can discard valid transport packets when different prefix offsets and broad header ranges produce a false handshake match. Profile input and the editor also hide invalid or unsupported settings.

The analysis used RIPDPI `941f92ada478ec202b7b335d0f0b487deeee763b` and amneziawg-go `v3.1.20260828` (`b5928efb6ca19f0153958460c3d141f04abc5c2e`). Existing successful payload tests do not prove timer, idle-packet, or load behavior. This change closes those gaps without copying unsafe upstream validation or claiming unmeasured DPI resistance.

## What Changes

- Apply the complete supported 3.1 timer, retry, expiry, cookie, and transport-padding behavior. Show actual support during intermediate implementation states.
- Authenticate receive candidates before committing to a packet type. Preserve MAC, AEAD, and replay checks.
- Validate keepalive text consistently across the editor, URI, and configuration import. Validate Header Protection prefix rules before enabling activation.
- Show and edit v3 settings, protect the Header Protection key, and make preset replacement of server-coordinated values explicit.
- Preserve bounded CPS/junk parsing, authenticated UDP-window learning, key storage, and existing routing/VPN protection.
- Expand host and Android peer checks to observe each feature, then require separate physical arm64 and exact-artifact acceptance.
- BREAKING validation correction: reject malformed keepalive ranges and nonzero ranges that start at zero instead of silently converting them to another interval or off. Preserve existing stored off values; do not invent lost source values.
- Wire behavior correction: pad outgoing keepalives and apply standard 16-byte data padding when custom padding is off. Ordinary WireGuard/AWG profiles must retain authenticated connectivity.

## Capabilities

### New Capabilities

- None; extend the existing native-engine capability through its configuration and acceptance paths.

### Modified Capabilities

- `awg3-native-engine`: complete behavioral support, safe profile handling, and feature-specific interoperability evidence.

## Impact

- `ripdpi-warp-core`, its boringtun boundary, and tests; Android engine API/service configuration tests; core data model/runtime-state/storage; AWG editor and ten locale sets if UI strings change.
- Existing JSON names, JNI signatures, Room data, and secret storage remain stable unless the backend decision proves a separately documented contract change is necessary.
- Fixture peers, host/Android runners, validators, and CI coverage expand. No server deployment, production access, cryptographic primitive replacement, or automatic lifting of the shared arm64 deployment guard is included.
- This commit creates planning artifacts only. Implementation, hosted CI, device, and artifact acceptance remain pending.
