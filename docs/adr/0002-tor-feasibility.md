# ADR 0002: Tor (Arti) Relay Backend Feasibility

> Status: **approved go (opt-in, bridge+PT only)**. Decision date: 2026-05-29. Records the size-feasibility gate behind the already-landed `ripdpi-tor` wiring (`feat(tor): wire relay backend`, commit `feb99a629`); the wiring is not reverted.

## Decision

Decision: **keep** the `arti-client`-backed Tor relay backend (`ripdpi-tor` -> `ripdpi-relay-tls-transports` -> `ripdpi-relay-core` as `RelayKind::Tor` / `RelayBackend::Tor`). The Android binary-size cost of Arti is bounded and acceptable for an opt-in transport, so the feasibility gate is GO rather than the Snowflake-style no-go.

This is a recorded GO. It does not authorize a direct-bootstrap default, UDP over Tor, or making Tor a default relay -- those remain out of scope per the `ripdpi-tor` README non-goals.

## Context

`ripdpi-tor` wraps `arti-client` 0.42.0 to provide TCP relay connections and DNS resolution over Tor. It is reachable end to end today: `transport_descriptor.rs` registers `build_tor`, `builders/tor.rs` constructs `RelayBackend::Tor` via `TorRelayBackend::from_bridge_pt_config`, and `RelayBackendConfig::Tor` / `RelayKind::Tor` resolve through the standard relay config path at schema version 6.

Arti is heavy: pulling `arti-client` into the relay cdylib adds `arti-client` plus 36 `tor-*` crates (`tor-proto`, `tor-netdir`, `tor-netdoc`, `tor-dirmgr`, `tor-circmgr`, `tor-guardmgr`, `tor-hsclient`, `tor-keymgr`, `tor-llcrypto`, ...). The crate's `arti-client` dependency is unconditional (no cargo feature gate), so the code is statically linked into the shipped `.so` for every user, whether or not they ever enable a Tor profile. "Is that size cost acceptable?" is the feasibility question this ADR settles, mirroring the Step 0 gate used for the Snowflake native-port decision.

## Measurement

Method: `cargo bloat --release --crates -p ripdpi-relay-android` (the cdylib that links the relay stack), run on the host target `aarch64-apple-darwin` -- the same `arm64` ISA as the primary shipped ABI `arm64-v8a`, so `.text` codegen is a faithful proxy. Overrides `CARGO_PROFILE_RELEASE_STRIP=none CARGO_PROFILE_RELEASE_OPT_LEVEL=z CARGO_PROFILE_RELEASE_LTO=off` keep per-crate symbol attribution intact while matching the shipped `android-jni` profile's `opt-level = "z"`.

Result -- `arti-client` plus the 36 `tor-*` crates contribute **~1.22 MiB of `.text`** out of the binary's 7.6 MiB total `.text` (~16%). Largest contributors:

| Crate | `.text` |
| --- | --- |
| `tor-proto` | 230 KiB |
| `arti-client` | 205 KiB |
| `tor-netdoc` | 127 KiB |
| `tor-circmgr` | 97 KiB |
| `tor-dirmgr` | 96 KiB |
| `tor-guardmgr` | 88 KiB |
| `tor-chanmgr` | 43 KiB |
| (30 more `tor-*` crates) | < 38 KiB each |

This figure is a **lower bound** on the true installed `.so` delta. It excludes `.rodata` / `.data.rel.ro`, the Arti-flavored transitive crypto crates (`rsa`, `curve25519-dalek`, `ed25519-dalek`, `fs-mistrust`, ...) which are small (each < 26 KiB of `.text`) and largely shared with the rest of the binary, and the difference between this no-LTO measurement and the shipped fat-LTO build. For context, the shipped libraries today (`scripts/ci/native-size-baseline.json`, stripped) are `libripdpi.so` 11.6 MiB on `arm64-v8a` and 7.5 MiB on `armeabi-v7a`. A precise per-ABI `.so` delta would require a differential build with `ripdpi-tor` temporarily behind a cargo feature; that was not run because the attributed `.text` already answers the gate.

## Rationale

GO, not no-go, because the only cost is binary size and that cost is bounded:

- ~1.2 MiB of `.text` (arm64) for a complete, upstream-maintained, hardened Tor client is a reasonable trade for the censorship-resistance capability it unlocks. It does not threaten the 128 KiB per-library growth gate on its own going forward, because the code is already linked and stable across Arti point releases.
- The Snowflake native-Rust no-go was driven by a **fingerprint-detectability regression** (webrtc-rs DTLS ClientHello matches the pre-hardening Pion class actively blocked in Russia), not by size. Tor-via-Arti has no analogous regression: censorship resistance is delivered by external pluggable transports (obfs4 / WebTunnel binaries) that Arti drives, and the threat model is enforced in code.
- The censored-network threat model is enforced, not assumed. `build_bridge_pt_config` requires at least one bridge line and at least one PT binary, and `required_bridge_transport` rejects bridge lines whose transport is empty / `-` / `bridge` as `DirectBridgeLine`. The relay builder only ever calls `from_bridge_pt_config`, so there is no direct-bootstrap path through the wired backend. `tests/bridge_pt_config.rs` pins all three behaviors; `relay-core` `tor_backend_builds_in_process_and_rejects_udp` pins `udp_capable = false`.

## Consequences

- `RelayKind::Tor` stays reachable; `builders/tor.rs` keeps calling `ripdpi-tor`; `udp_capable` stays `false` (TCP connect + DNS resolve only).
- Every shipped build pays the ~1.2 MiB+ Arti `.text` cost regardless of whether the user enables Tor, because `arti-client` is an unconditional dependency. This is the main downside on record.
- Tor is opt-in and is not a default relay. The Android opt-in surface must carry the latency/anonymity caveat (Tor is slower and has a different trust model than the low-latency relays).
- `native/rust/crates/ripdpi-tor/README.md` states wired + verified + the measured size cost; this ADR is the authoritative size record.

## Revisit Trigger

Revisit if any of these fire:

- The native-size CI gate (`scripts/ci/native-size-baseline.json`, 128 KiB per-library / 256 KiB total growth) becomes hard to hold and Arti is implicated. The first mitigation to evaluate is **feature-gating `arti-client`** behind a cargo feature so non-Tor builds drop the ~1.2 MiB, rather than dropping the backend.
- A materially leaner native Tor client becomes available, or Arti's footprint grows by more than ~25% in a future major release.
- A requirement appears for UDP over Tor or onion-service hosting (both currently out of scope).
