# Shadowsocks core integration

This directory contains `shadowsocks` 1.25.0, licensed under MIT. The upstream
package is marked `passively-maintained`.

- Source: https://github.com/shadowsocks/shadowsocks-rust
- Source commit: `ab388c7466d21f979430e33cc9ef10e22fb05955`
- Package: https://static.crates.io/crates/shadowsocks/shadowsocks-1.25.0.crate
- Package SHA-256: `e2065b026dbe4f47048eca384adf07f693bf901050d80abd3adb7b2422709b2d`
- License: [LICENSE](LICENSE)

`src/packet_window.rs` comes from the matching `shadowsocks-service` 1.25.0
package. It retains its MIT SPDX and WireGuard copyright notices, algorithm,
and tests. Rust sources use the repository's rustfmt settings.

- Source: https://github.com/shadowsocks/shadowsocks-rust/blob/ab388c7466d21f979430e33cc9ef10e22fb05955/crates/shadowsocks-service/src/net/packet_window.rs
- Service package: https://static.crates.io/crates/shadowsocks-service/shadowsocks-service-1.25.0.crate
- Service package SHA-256: `999a4b33e1e211604bf7d8bd67e58d56701ebdf5adfdd2d1141105e61c76d641`
- Upstream packet-window file SHA-256: `92167224c0c65a0155fe55e36a59435432dc9f6ecf98d2bbbf8b48ca3fe04f15`

## Local changes

[integration.patch](integration.patch) records all code changes from the core
package, including the additional packet-window file and regression tests.
The BLAKE3 dependency enables `no_neon` to preserve the Android shared-library
export contract; its version requirement is unchanged.

- A per-context timestamp source supports calibrated network time. TCP writers
  retain the source when a stream is created. UDP and TCP authentication use it.
- Successful UDP decoding returns the authenticated peer timestamp. Association
  validation and clock calibration remain the caller's responsibility.
- UDP session IDs use byte-array reads without alignment assumptions.
- UDP nonce, padding, and tag storage is initialized before use. TCP encryption
  writes into initialized slices without overlapping mutable references.
- TCP readers clone the reference-counted key before mutating reader state.
- The minimum server UDP header includes the client-session ID. A short
  authenticated header returns an error instead of a buffer-read panic.
- The UDP cipher cache uses a key digest instead of an allocation address. Its
  ordering compares the complete cache key. ChaCha entries do not vary by packet
  bytes because this cipher uses the pre-shared key directly.
- The core exports the upstream service packet-window filter.
- AEAD writers acknowledge owned plaintext before backpressure can suspend a
  later write. Flush and shutdown drain ciphertext; reads also drive pending
  output without blocking the receive direction. This prevents data loss after
  cancellation of a write.
- Debug output redacts credentials, and EIH logs omit stable identity hashes.

## Reproduce

Download the core package from the URL above and verify its SHA-256. Extract it
into an empty directory, then apply the recorded patch from the package root:

```sh
patch -p1 < /path/to/integration.patch
```

The resulting source files must match this directory. `README.ripdpi.md` and
`integration.patch` are integration records, not upstream package files. Do not
regenerate the patch from another release or include build outputs.

The application disables default features and enables `aead-cipher`,
`aead-cipher-2022`, and `security-replay-attack-detect`. It retains its own method
allowlist, protected socket creation, and bounded UDP association policy.
