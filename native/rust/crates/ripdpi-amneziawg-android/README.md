# ripdpi-amneziawg-android

**Layer:** L8 — Android / JNI adapter. **Artifact:** `cdylib` → `libripdpi-amneziawg.so`, loaded by `RipDpiAmneziaWgNativeLoader` through `System.loadLibrary("ripdpi-amneziawg")`.

## Boundary owner

`core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiAmneziaWg.kt` owns the Kotlin binding and lifecycle wrapper. The crate exposes the create/start/stop/destroy, telemetry, readiness, and VPN-protect registration surface for user-configured AmneziaWG sessions.

## Native composition

The adapter composes `ripdpi-warp-core`'s shared WireGuard/AmneziaWG data plane with `ripdpi-native-protect` and `android-support`. It does not perform Cloudflare WARP enrollment and is shipped separately from `libripdpi-warp.so`.

## Invariants

- JNI exports stay behind `android_support::ffi_boundary`; panics must not cross JNI.
- Runtime readiness is delivered through the registered one-shot callback.
- Every non-loopback outbound socket must pass the VPN-protect registration before connect.
- Session handles and protect-generation tokens are lifecycle-scoped and must be released during stop/destroy.

See [`JNI_CONTRACT.md`](../../../../docs/architecture/JNI_CONTRACT.md), [`TELEMETRY_CONTRACT.md`](../../../../docs/architecture/TELEMETRY_CONTRACT.md), and [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md).
