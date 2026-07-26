# ripdpi-warp-android

**Layer:** L8 — Android / JNI adapters.
**Artifact:** `cdylib` → `libripdpi-warp.so`.

## Boundary owner (Kotlin)

`RipDpiWarpNativeBindings` (in
`core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiWarp.kt`). It runs the
WARP / AmneziaWG runtime behind `jniCreate` / `jniStart` / `jniStop` /
`jniPollTelemetry` / `jniDestroy`, plus `jniExecuteProvisioning` and
`jniProbeEndpoint`, and its own `jniRegisterVpnProtect` /
`jniUnregisterVpnProtect`, readiness-listener registration, and quality
telemetry projection. WARP ships as its own `.so`, separate from
`libripdpi.so`.

## Rust crates it calls

`ripdpi-warp-core` (the native WARP runtime + AmneziaWG codec),
`ripdpi-native-protect` (the `VpnService.protect` callback registry, used by
`src/vpn_protect.rs`), `ripdpi-quality`, `ripdpi-tls-profiles`, `android-support`.

## JNI handle / error / panic / lifecycle expectations

- `JNI_OnLoad` runs `lifecycle::jni_on_load` under `catch_unwind`
  (→ `JNI_ERR` on panic).
- A WARP session is a `jlong` handle; lifecycle is
  `create → start → stop → destroy`. `jniStart` returns an `Int` status
  (`-1` panic sentinel); telemetry/provisioning/probe exports return `jstring`.
- Every export goes through `android_support::ffi_boundary`.
- `jniRegisterVpnProtect` stores a `JavaVM` + `VpnService` `GlobalRef`; register
  and unregister must be symmetric with the VPN service lifecycle.

## Plane

**Control-plane** at the JNI seam. The WARP tunnel data plane runs natively in
`ripdpi-warp-core`; every non-loopback socket it opens must be protected via the
registered protect callback.

---
See [`JNI_CONTRACT.md`](../../../../docs/architecture/JNI_CONTRACT.md) for the
boundary contract and
[`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) for the crate
taxonomy.
