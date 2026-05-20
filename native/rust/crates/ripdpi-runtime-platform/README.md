# ripdpi-runtime-platform

**Layer:** L5 — platform / privileged.

## Responsibility

The platform-primitive layer. It owns the OS-level operations the runtime
needs — socket options, TTL operations, raw IPv4/IPv6 packet emission, IP
fragmentation, fake-send, retransmission, `VpnService.protect` dispatch,
capability detection, and the root-helper IPC client. Each privileged
operation checks `with_root_helper()` first and falls back to a local
non-privileged path.

## What belongs here

- OS-primitive modules: `socket`, `socket_options`, `tcp`, `ttl` / `ttl_ops`,
  `raw_packet`, `ip_fragmentation`, `fake_send`, `retransmit`, `ipv4_ids`,
  `original_destination`, `io_uring`, `bpf_timestamp`.
- `protect` / `vpn_protect` — the `VpnService.protect` callback dispatch.
- `root_helper` / `root_helper_client` — root-helper registry and IPC client.
- `capability` / `capabilities` — device capability detection.

## What must not be added here

- Policy, strategy, or adaptive *decision* logic — those are L3.
- Runtime composition / wiring — that is L4.
- `jni` / `android-support` — this crate defines the platform **port**; the
  Android adapters implement the JNI side. It must stay JNI-free.

## Dependencies

- **Upstream:** `ripdpi-capabilities`, `ripdpi-config`, `ripdpi-desync`,
  `ripdpi-ipfrag`, `ripdpi-native-protect`, `ripdpi-privileged-ops`,
  `ripdpi-root-helper-protocol`, `ripdpi-io-uring`; plus `libc`, `nix`,
  `serde`, `serde_json`, `tracing`.
- **Downstream (≈8 consumers):** `ripdpi-proxy-runtime-adapter`,
  `ripdpi-proxy-runtime-desync-adapter`, `ripdpi-tunnel-android`,
  `ripdpi-tunnel-intercept`, `ripdpi-monitor-engine`,
  `ripdpi-diagnostics-candidates`, `ripdpi-ws-bootstrap`, and the Android
  platform adapter.

## Public API stability

High fan-in platform surface — the `protect`, `raw_packet`, `socket`, `tcp`,
and `ttl_ops` public modules are consumed across runtime, tunnel, and
diagnostics. Treat signature changes as breaking. Every privileged path must
preserve the non-root fallback (see the non-root baseline in
[`RUNTIME_MODES.md`](../../../../docs/architecture/RUNTIME_MODES.md)).

---
Part of the RIPDPI native Rust workspace — see
[`docs/architecture/NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md)
for the full crate taxonomy and dependency-direction policy.
