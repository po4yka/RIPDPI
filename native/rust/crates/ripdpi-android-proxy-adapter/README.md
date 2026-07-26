# ripdpi-android-proxy-adapter

**Layer:** L8 — Android / JNI adapters.
**Artifact:** library, linked into `libripdpi.so`.

## Boundary owner (Kotlin)

`RipDpiProxyNativeBindings` (in `core/engine/.../core/RipDpiProxy.kt`), wrapped
by the coroutine-facing `RipDpiProxy` runtime. This adapter implements the
proxy-session lifecycle behind `jniCreate` / `jniStart` / `jniStop` /
`jniPollTelemetry` / `jniDestroy` / `jniUpdateNetworkSnapshot`, plus geo-database
exports. The `ripdpi-android` facade forwards to it.

## Rust crates it calls

`ripdpi-proxy-runtime` (the proxy runtime), `ripdpi-runtime-api` (the
`EmbeddedProxyControl` / telemetry-sink ports), `ripdpi-proxy-config` (config
translation), `ripdpi-config`,
`ripdpi-android-telemetry-adapter`, `ripdpi-android-bridge-support`,
`ripdpi-failure-classifier`, `ripdpi-quality`, `ripdpi-runtime-decision-ports`,
and `android-support`.

## JNI handle / error / panic / lifecycle expectations

- `jniCreate` returns a `jlong` `HandleRegistry` handle (`0` on failure);
  lifecycle ordering is `create → start → stop → destroy`.
- `jniStart` runs the **blocking** proxy event loop — Kotlin invokes it on the
  IO dispatcher under the wrapper's mutex.
- The `ripdpi-android` facade wraps these entry functions in
  `android_support::ffi_boundary`; adapter-side errors surface as `JniProxyError`
  Java exceptions (`ripdpi-android-bridge-support`).
- Holds no `JNI_OnLoad`; lifecycle state lives in the adapter's session registry.

## Plane

**Control-plane.** Drives proxy lifecycle and 1 Hz telemetry polling; the proxy
data plane runs entirely native, with no JNI on the hot path.

---
See [`JNI_CONTRACT.md`](../../../../docs/architecture/JNI_CONTRACT.md) for the
boundary contract and
[`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) for the crate
taxonomy.
