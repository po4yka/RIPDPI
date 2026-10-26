# Change: Run AmneziaWG 3.x configs

Task ID: `TRN-1792949035079937`

## Why

AmneziaVPN 5.x (amnezia-client 5.0.3.0) creates AWG 3.1 servers. Their configs add `HeaderProtectionKey`, `ContentPaddingAddition`, `RekeyAfterTime`, `RekeyTimeout`, `RejectAfterTime`, `KeepaliveTimeout`, `MaxHandshakeAttempts`, `RandomTrailers`, `DisableCookies` and a `PersistentKeepalive` range. RIPDPI rejected every such config at parse time, and its native engine had none of these features.

## What Changes

- `ripdpi-warp-core` gains the AWG 3.x envelope: ChaCha20 header protection, random handshake trailers, transport content padding inside a UDP window, `RekeyAfterTime` and the keepalive range. The semantics are in `docs/native/amneziawg-3x-wire-semantics.md`.
- The Kotlin parser, `AwgProfileForm.v3`, the activation request, the `amneziawg://` codec, the credential store (header protection key outside Room) and the native JSON carry the new keys. `AmneziaWgProfile.toWgQuickConf()` renders a profile back to `.conf`.
- The arm64 S3/S4 floor is lifted on Android arm64 emulator evidence. Header protection needs S3/S4 >= 12, so AmneziaVPN 5.x profiles now start. A physical device revalidation of amneziawg-go#110 is still open.

## Capabilities

### New Capabilities

- `awg3-native-engine`: AWG 3.x wire behaviour and its configuration contract.

### Modified Capabilities

- None.

## Impact

- Native `ripdpi-warp-core` (one new lock edge to the already-locked `chacha20 0.10.0`), `core/data`, `core/engine-api`, `core/service`, and the app editor.
- The cohort fingerprint and the bundle schema do not cover the 3.x keys; changing them needs a coordinated deploy-side change and a golden bless.
