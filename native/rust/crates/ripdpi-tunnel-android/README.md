# ripdpi-tunnel-android

**Layer:** L8 — Android / JNI adapters.
**Artifact:** `cdylib` → `libripdpi-tunnel.so` (loaded via `System.loadLibrary("ripdpi-tunnel")`).

## Boundary owner (Kotlin)

`Tun2SocksNativeBindings` (in
`core/engine/src/main/kotlin/com/poyka/ripdpi/core/Tun2SocksTunnel.kt`), wrapped
by the coroutine-facing `Tun2SocksTunnel`. Used in **VPN mode only**: it runs
the TUN-to-SOCKS bridge behind `jniCreate` / `jniStart(handle, tunFd)` /
`jniStop` / `jniGetStats` / `jniGetIcmpIngressPackets` / `jniGetTelemetry` /
`jniDestroy`, plus flow-attribution, direct-DNS binder, and PCAP bridge exports.

## Rust crates it calls

`ripdpi-tunnel-core` (the TUN-to-SOCKS runtime), `ripdpi-tunnel-config`,
`ripdpi-runtime-platform` (raw-packet / TUN-egress platform primitives),
`ripdpi-flow-app-attribution`, `ripdpi-pcap`, `ripdpi-quality`,
`ripdpi-telemetry`, and `android-support`.

## JNI handle / error / panic / lifecycle expectations

- `JNI_OnLoad` (`src/lib.rs`) calls `ignore_sigpipe`,
  `init_android_logging("ripdpi-tunnel-native")`, `install_panic_hook`;
  `catch_unwind` → `JNI_ERR` on panic.
- `jniStart(handle, tunFd)` duplicates the Android-owned TUN fd, spawns the
  tunnel worker, and waits for its bounded readiness barrier before returning;
  packet I/O remains on the worker thread. `jniStop` cancels via
  `CancellationToken` and joins the worker.
- Sessions are `jlong` handles; every export goes through `ffi_boundary`
  (`jstring`→null, `jlong`→0 sentinels).
- `libripdpi-tunnel.so` forwards to the selected local SOCKS egress. That egress may be the proxy engine, relay runtime, WARP, or AmneziaWG composition; it is not inherently tied to `libripdpi.so`.

## Plane

**Control-plane** at the JNI seam. The data plane (TUN packet pump) runs on the
native worker thread inside `ripdpi-tunnel-core` with no JNI on the hot path.

---
See [`JNI_CONTRACT.md`](../../../../docs/architecture/JNI_CONTRACT.md) for the
boundary contract and
[`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) for the crate
taxonomy.
