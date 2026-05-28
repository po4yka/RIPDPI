# ripdpi-proxy-config

**Layer:** L2 — contracts / config.

## Responsibility

The shared proxy-config translation crate. It takes the native config JSON
produced by Kotlin (`RipDpiProxyJsonCodec`), CLI arguments, diagnostics
recommendation drafts, and automatic-probing candidate overlays and normalizes
them all into one `RuntimeConfig` / `RuntimeConfigEnvelope` shape that the
runtime consumes. One config shape, not three loosely-matching serializers.

## What belongs here

- The `RuntimeConfig` / `RuntimeConfigEnvelope` types and the `ProxyUi*` config
  structs (`src/types/`).
- `parse_proxy_config_json` and the `runtime_config_from_{ui,command_line,payload}`
  conversions (`src/convert/`).
- The string→enum `parse_*` helpers (desync mode, chain-step kinds, fake/TLS/
  QUIC profiles) and built-in `presets`.
- serde defaults for every field, so older config JSON keeps loading.

## What must not be added here

- Runtime behavior, I/O, sockets, or async — this crate only *describes* and
  *translates* config.
- Dependencies on any L3+ crate (domain logic, runtime, platform, adapters).
- `jni` / `android-support` — this crate is JNI-free.

## Dependencies

- **Upstream:** `ripdpi-config`, `ripdpi-packets`; `serde`, `serde_json`, `thiserror`.
- **Downstream (16 direct consumers):** the runtime crates (`ripdpi-runtime-api`,
  `-adaptive`, `-decision-ports`, `-services`, `ripdpi-proxy-runtime-adapter`,
  `ripdpi-proxy-runtime-desync-adapter`), the diagnostics crates, the monitor
  adapter, `ripdpi-ws-bootstrap`, and the Android proxy/telemetry adapters.

## Public API stability

High fan-in — treat the public types and `parse_*` functions as a wire-style
contract. New fields **must** be `#[serde(default)]`; never rename a serde key
or repurpose a config field. See [`CONFIG_CONTRACTS.md`](../../../../docs/architecture/CONFIG_CONTRACTS.md) §3.

---
Part of the RIPDPI native Rust workspace — see
[`docs/architecture/NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md)
for the full crate taxonomy and dependency-direction policy.
