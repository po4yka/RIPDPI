# ripdpi-runtime-api

**Layer:** L2 — contracts / config.

## Responsibility

The runtime API / port surface shared between the proxy runtime and everything
that drives or observes it. It defines the embedded-control handle, the
telemetry sink, the background-probe interface, and the network-snapshot type —
the stable seam across which the runtime is started, stopped, polled, and fed.

## What belongs here

- `EmbeddedProxyControl` — the shutdown/control handle for an embedded proxy session.
- `RuntimeTelemetrySink` and the `install/current/clear_runtime_telemetry`
  process-global telemetry accessors.
- `BackgroundProbes` and the network-snapshot type.
- Lightweight shared sync primitives used by these types.

## What must not be added here

- Runtime *implementation* — the proxy loop, session handling, decision logic.
- OS primitives, sockets, async I/O.
- Dependencies on L3+ crates (policy, strategy, adaptive, services, adapters).
- `jni` / `android-support` — this crate is JNI-free.

## Dependencies

- **Upstream:** `ripdpi-failure-classifier`, `ripdpi-proxy-config`; `arc-swap`
  (and `loom` for concurrency tests).
- **Downstream (≈8 consumers):** `ripdpi-proxy-runtime`,
  `ripdpi-proxy-runtime-adapter`, `ripdpi-proxy-runtime-desync-adapter`,
  `ripdpi-runtime-services`, `ripdpi-monitor-proxy-runtime`, the Android
  proxy/telemetry adapters, and `ripdpi-cli`.

## Public API stability

This is a contract crate — `EmbeddedProxyControl`, `RuntimeTelemetrySink`, and
`BackgroundProbes` are consumed across the runtime, monitor, and Android
layers. Treat signature changes as breaking; telemetry shapes are golden-locked
downstream.

---
Part of the RIPDPI native Rust workspace — see
[`docs/architecture/NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md)
for the full crate taxonomy and dependency-direction policy.
