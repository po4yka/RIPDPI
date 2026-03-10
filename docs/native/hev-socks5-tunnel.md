# hev-socks5-tunnel Usage

## Role in RIPDPI

`hev-socks5-tunnel` is used only in VPN mode. It takes the Android TUN file descriptor, reads packets from it, and forwards traffic to the local SOCKS5 proxy started by `libripdpi.so`.

The built shared library is `libhev-socks5-tunnel.so`.

## App Call Chain

Start path:

`RipDpiVpnService.startTun2Socks()` -> `Tun2SocksTunnel.start(config, tunFd)` -> `jniCreate(configJson)` -> `jniStart(handle, tunFd)` -> native worker thread -> `hs5t_core::run_tunnel()`

Stop path:

`RipDpiVpnService.stopTun2Socks()` -> `Tun2SocksTunnel.stop()` -> `jniStop(handle)` -> `CancellationToken::cancel()` -> worker thread join

Relevant sources:

- `core/service/src/main/java/com/poyka/ripdpi/services/RipDpiVpnService.kt`
- `core/engine/src/main/java/com/poyka/ripdpi/core/Tun2SocksTunnel.kt`
- `native/rust/crates/hs5t-android/src/lib.rs`

## Methods Actually Used

| Method | Defined in | Reached from | Current status | Purpose |
| --- | --- | --- | --- | --- |
| `hs5t_core::run_tunnel` | `native/rust/third_party/hev-socks5-tunnel/crates/hs5t-core/src/lib.rs` | `jniStart(handle, tunFd)` worker thread | Used | Runs the tunnel runtime from the in-memory config and Android TUN fd. |
| `CancellationToken::cancel` | `tokio-util` | `jniStop(handle)` | Used | Requests tunnel shutdown from another thread. |
| `Stats::snapshot` | `native/rust/third_party/hev-socks5-tunnel/crates/hs5t-core/src/stats.rs` | `jniGetStats(handle)` | Used | Returns packet and byte counters. |
| tunnel telemetry snapshot assembly | `native/rust/crates/hs5t-android/src/lib.rs` | `jniGetTelemetry(handle)` | Used | Returns tunnel lifecycle, counters, last error, and a bounded drained event ring. |

## JNI Surface Exposed to Kotlin

`Tun2SocksTunnel.kt` now exposes a handle-based native contract:

- `jniCreate(configJson)`
- `jniStart(handle, tunFd)`
- `jniStop(handle)`
- `jniGetStats(handle)`
- `jniGetTelemetry(handle)`
- `jniDestroy(handle)`

Compatibility details preserved by the Rust JNI shim:

- `jniStart(handle, tunFd)` still returns `Unit` immediately.
- The Rust bridge owns the worker thread internally, just like the old JNI C layer.
- `jniGetStats(handle)` keeps the array order `[tx_pkt, tx_bytes, rx_pkt, rx_bytes]`, and Kotlin maps it into `TunnelStats`.
- `jniGetTelemetry(handle)` returns a JSON snapshot that Kotlin maps into `NativeRuntimeSnapshot`.

## Runtime Dependencies

The old Android C tunnel stack is gone. The Rust tunnel runtime now builds from in-repo crates and links to:

- `libc.so`
- `libdl.so`
- `libm.so`

The Rust crate graph is centered on:

- `hs5t-core`
- `hs5t-session`
- `hs5t-tunnel`
- `tokio`
- `smoltcp`
- `fast-socks5`
- `serde`
- `tokio-util`

## Android-specific Notes

- RIPDPI now starts the tunnel with an in-memory JSON config payload and an already established Android TUN fd.
- The config still points the tunnel to the local SOCKS5 proxy on `127.0.0.1:$port`.
- `libhev-socks5-tunnel.so` therefore still depends on `libripdpi.so` already being active.
- `RipDpiVpnService` polls tunnel telemetry while the VPN is running and merges it with proxy telemetry from `libripdpi.so`.

## Passive Tunnel Runtime Telemetry

While the VPN service is running, `Tun2SocksTunnel.telemetry()` calls `jniGetTelemetry(handle)` and receives:

- tunnel state and health
- cumulative session count
- cumulative native error count
- upstream SOCKS5 address
- packet and byte counters mirrored from `Stats::snapshot`
- last native error
- a bounded drained event ring

The drained event ring records:
- tunnel start
- explicit stop requests
- clean tunnel stop
- worker errors and worker panic fallback
