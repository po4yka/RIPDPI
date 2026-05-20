# ripdpi-android

**Layer:** L8 — Android / JNI adapters.
**Artifact:** `cdylib` → `libripdpi.so` (loaded by `RipDpiNativeLoader` via `System.loadLibrary("ripdpi")`).

## Boundary owner (Kotlin)

The JNI **export facade** for the main native library. It carries the `Java_*`
symbols for `RipDpiProxyNativeBindings` (`RipDpiProxy.kt`),
`NetworkDiagnosticsNativeBindings` (`NetworkDiagnostics.kt`),
`StrategyEngineNativeBindings`, `RipDpiCdnEchNativeBindings`,
`RipDpiSharedPriorsNativeBindings`, `RipDpiPlatformCapabilities`,
`NativeOwnedTlsHttpFetcherNativeBindings`, `NativeEchTlsHandshakeBridge`, and the
`NativeDoqQuicClientNativeBindings` / `NativeQuicInitialPacketBindings` /
`JniNativeSignsBridge` diagnostics/detection classes.

`src/ffi.rs` defines the `export_jni!` macro; `src/ffi/*` are thin bridge
modules that delegate all real work to the adapter crates below. Keep this
crate a loader/export boundary, not a feature hub.

## Rust crates it calls

The seven `ripdpi-android-*` adapter crates (`-bridge-support`,
`-diagnostics-adapter`, `-fetch-adapter`, `-platform-adapter`, `-proxy-adapter`,
`-telemetry-adapter`, `-vpn-protect-adapter`) plus `android-support`,
`ripdpi-strategy-config`, and `ripdpi-strategy-lua`.

## JNI handle / error / panic / lifecycle expectations

- `JNI_OnLoad` (`src/lib.rs`) stores the `JavaVM`, calls `ignore_sigpipe`,
  `init_android_logging("ripdpi-native")`, `install_panic_hook`, and installs
  the telemetry recorder; it `catch_unwind`s and returns `JNI_ERR` on panic.
- Every export is wrapped by `export_jni!` → `android_support::ffi_boundary`
  with a sentinel return (`jstring`→null, `jlong`→0, `jboolean`→`JNI_FALSE`,
  `jint`→a caller-recognized failure code). Unwinding across `extern "system"`
  is UB and must never happen.
- Sessions are opaque `jlong` handles from a `HandleRegistry`; `0` = no handle.
- Errors map to Java exceptions via `ripdpi-android-bridge-support`.

## Plane

**Control-plane.** The crate itself is the JNI control boundary. The data plane
(proxy runtime, desync) runs in the linked-in native crates with **no JNI on
the per-packet path**.

---
See [`JNI_CONTRACT.md`](../../../../docs/architecture/JNI_CONTRACT.md) for the
boundary contract and
[`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) for the crate
taxonomy.
