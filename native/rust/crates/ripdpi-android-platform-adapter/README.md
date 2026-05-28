# ripdpi-android-platform-adapter

**Layer:** L8 — Android / JNI adapters.
**Artifact:** library, linked into `libripdpi.so`.

## Boundary owner (Kotlin)

Several platform / diagnostics-detail JNI classes:
`RipDpiPlatformCapabilities` (capability probes, e.g. `jniSeqovlSupported`),
`RipDpiCdnEchNativeBindings` (CDN/ECH refresh + seed),
`RipDpiSharedPriorsNativeBindings` (offline-learner shared priors),
`NativeDoqQuicClientNativeBindings` and `NativeQuicInitialPacketBindings`
(`:core:diagnostics` DoQ / QUIC-initial), and
`StrategyEngineNativeBindings.injectProbeResultsJson`.

## Rust crates it calls

`ripdpi-runtime-platform` (capability detection, platform primitives),
`ripdpi-runtime-strategy`, `ripdpi-shared-priors`, `ripdpi-dns-resolver`,
`ripdpi-diagnostics-dns`, and `ripdpi-packets`.

## JNI handle / error / panic / lifecycle expectations

- Most exports are stateless request/response calls returning a `jstring` JSON
  payload (null on panic) or a `jboolean` capability flag (`JNI_FALSE` on
  panic) — not handle-based sessions.
- Entry functions use `jni::EnvUnowned::with_env` and return nullable JSON
  strings or capability booleans; the `ripdpi-android` facade owns the outer
  JNI export and panic boundary.
- The shared-priors path is fail-secure: malformed input must never replace a
  known-good config.

## Plane

**Control-plane.** Capability detection, ECH refresh, and shared-priors
application are configuration/control operations, not packet processing.

---
See [`JNI_CONTRACT.md`](../../../../docs/architecture/JNI_CONTRACT.md) for the
boundary contract and
[`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) for the crate
taxonomy.
