# ripdpi-tunnel-android

**Layer:** L8 — Android / JNI adapters.
**Artifact:** `cdylib` → `libripdpi-tunnel.so` (loaded via `System.loadLibrary("ripdpi-tunnel")`).

## Boundary owner (Kotlin)

`Tun2SocksNativeBindings` (in
`core/engine/src/main/kotlin/com/poyka/ripdpi/core/Tun2SocksTunnel.kt`), wrapped
by the coroutine-facing `Tun2SocksTunnel`. Used in **VPN mode only**: it runs
the TUN-to-SOCKS bridge behind `jniCreate` / `jniStart(handle, tunFd)` /
`jniStop` / `jniGetStats` / `jniGetTelemetry` / `jniDestroy`.

## Rust crates it calls

`ripdpi-tunnel-core` (the TUN-to-SOCKS runtime), `ripdpi-tunnel-config`,
`ripdpi-runtime-platform` (raw-packet / TUN-egress platform primitives),
`ripdpi-telemetry`, `android-support`.

## JNI handle / error / panic / lifecycle expectations

- `JNI_OnLoad` (`src/lib.rs`) calls `ignore_sigpipe`,
  `init_android_logging("ripdpi-tunnel-native")`, `install_panic_hook`;
  `catch_unwind` → `JNI_ERR` on panic.
- `jniStart(handle, tunFd)` **adopts** the Android TUN fd and spawns the tunnel
  worker thread — it is **non-blocking** (returns after worker launch); `jniStop`
  cancels via `CancellationToken` and joins the worker.
- Sessions are `jlong` handles; every export goes through `ffi_boundary`
  (`jstring`→null, `jlong`→0 sentinels).
- `libripdpi-tunnel.so` requires `libripdpi.so` already active — the tunnel
  forwards into that library's local SOCKS endpoint.

## Plane

**Control-plane** at the JNI seam. The data plane (TUN packet pump) runs on the
native worker thread inside `ripdpi-tunnel-core` with no JNI on the hot path.

---
See [`JNI_CONTRACT.md`](../../../../docs/architecture/JNI_CONTRACT.md) for the
boundary contract and
[`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) for the crate
taxonomy.
