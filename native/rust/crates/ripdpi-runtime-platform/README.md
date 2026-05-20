# ripdpi-runtime-platform

**Layer:** L5 — platform / privileged.

## Responsibility

The platform port — the runtime's single window onto OS-level networking. It
does **not** implement privileged syscalls itself; those live in
`ripdpi-privileged-ops`, `ripdpi-native-protect`, `ripdpi-io-uring`, and the
`ripdpi-root-helper-*` crates. What this crate adds is *adaptation*: a stable,
organized public surface, non-root / non-Linux fallbacks, and the
root-helper-vs-local dispatch every privileged operation needs.

## Module taxonomy

Every module has exactly one of three roles; each module file repeats its role
in its own `//!` header.

### 1. Public facade modules (`pub mod`)

Thin `pub use` aggregators — no logic, only a stable, named public surface:
`capability`, `experimental`, `protect`, `raw_packet`, `socket`, `tcp`,
`ttl_ops`, `vpn`.

### 2. OS-primitive adapter modules (private `mod`)

Thin `#[cfg]`-split wrappers over a single upstream primitive crate
(`ripdpi-privileged-ops` / `ripdpi-io-uring`), each with a non-Linux fallback.
They hold no process state and perform no root-helper dispatch:
`bpf_timestamp`, `capabilities`, `io_uring`, `original_destination`,
`process`, `retransmit`, `socket_options`, `tcp_info`, `ttl`.

### 3. Runtime-adaptation modules

Modules that carry process-global state, own a registry, or choose between the
privileged root helper and a local path at call time: `vpn_protect`,
`experimental_tier3`, `fake_send`, `ip_fragmentation`, `ipv4_ids`, and the
`root_helper` / `root_helper_client` pair.

## What must not be added here

- Policy, strategy, or adaptive *decision* logic — those are L3.
- Runtime composition / wiring — that is L4.
- `jni` / `android-support` — this crate defines the platform **port**; the
  Android adapters implement the JNI side. It must stay JNI-free.
- Privileged syscall implementations — those belong in `ripdpi-privileged-ops`
  (`src/tests.rs` asserts there is no local `platform/linux` tree).

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

High fan-in platform surface — the `capability`, `raw_packet`, `socket`,
`tcp`, and `ttl_ops` facade modules are consumed across runtime, tunnel, and
diagnostics. Treat signature changes as breaking. Every privileged path must
preserve the non-root fallback (see the non-root baseline in
[`RUNTIME_MODES.md`](../../../../docs/architecture/RUNTIME_MODES.md)).

## Follow-ups

Boundary cleanups identified during the L5 module audit but intentionally
deferred — none is a safe mechanical change today:

- **`process` under the `capability` facade.** `detected_parallelism` and
  `install_shutdown_signal_handlers` are process/thread/signal primitives, not
  capability detection; they are surfaced through `capability` for historical
  reasons. Splitting them into a dedicated facade module is a public-path
  change (`capability::detected_parallelism` → `process::detected_parallelism`)
  and would need every downstream caller updated plus a compatibility
  re-export.
- **`protect` facade vs direct `ripdpi-native-protect` use.** `pub mod protect`
  re-exports `ripdpi-native-protect`, but current workspace consumers import
  `ripdpi-native-protect` directly and have zero references to
  `ripdpi_runtime_platform::protect`. Either narrow `protect` to `pub(crate)`
  (it would then exist only for the internal `vpn_protect` dispatch) or route
  consumers through it — a deliberate decision, not a side-effect.
- **`root_helper_client` visibility.** `pub mod root_helper_client` has no
  external workspace consumers; a live `RootHelperClient` is reachable only
  through the `root_helper` registry. It is a `pub(crate)` candidate, pending
  the same deliberate decision as `protect`.
- **OS-primitive sub-crate.** The nine OS-primitive adapter modules are a
  cohesive, dispatch-free group. If the crate grows further they could move to
  a dedicated `ripdpi-os-primitives` crate, leaving this crate as pure runtime
  adaptation. Not worth the churn at the current size.

---
Part of the RIPDPI native Rust workspace — see
[`docs/architecture/NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md)
for the full crate taxonomy and dependency-direction policy.
