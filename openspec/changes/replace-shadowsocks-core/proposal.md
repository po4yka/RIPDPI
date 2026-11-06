# Change: Replace the Shadowsocks protocol core with upstream

Task ID: `RLY-1793949568813382`

## Why

The app maintains its own SIP004 and SIP022 encryption framing, replay windows, and address codecs. The local peer fixture uses the same codec. This duplicates a maintained implementation and rejects SS2022 identity-key profiles.

## What Changes

- Use the upstream Shadowsocks library for relay TCP and UDP protocol operations.
- Keep protected sockets, explicit underlay binding, calibrated network time, bounded replay state, and cancellation ownership.
- Accept supported SS2022 identity-key profiles without adding legacy stream ciphers.
- Replace the fixture codec with an upstream server and retain observable protocol and lifecycle tests.
- BREAKING: remove the private ripdpi-shadowsocks codec API and update all internal consumers.

## Capabilities

### New Capabilities

- `upstream-shadowsocks-relay`: upstream protocol ownership with Android transport and time integration.

### Modified Capabilities

- None. The relay profile and JNI field layout remains unchanged.

## Impact

- Rust Shadowsocks crate, relay TLS transports, local network fixtures, relay tests, dependency locks, and native dependency documentation.
- Kotlin method-level capability projection where needed to match the verified upstream protocol matrix.
- No backend service, external account, or production credential is required.
