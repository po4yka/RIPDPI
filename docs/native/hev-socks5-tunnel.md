# hev-socks5-tunnel Usage

## Role in RIPDPI

`hev-socks5-tunnel` is used only in VPN mode. It takes the Android TUN file descriptor, reads packets from it, and forwards traffic to the local SOCKS5 proxy started by `libripdpi.so`.

The built shared library is `libhev-socks5-tunnel.so`.

## App Call Chain

Start path:

`RipDpiVpnService.startTun2Socks()` -> `TProxyService.TProxyStartService(configPath, tunFd)` -> `hs5t-jni` worker thread -> `hs5t_config::Config::from_file()` -> `hs5t_core::run_tunnel()`

Stop path:

`RipDpiVpnService.stopTun2Socks()` -> `TProxyService.TProxyStopService()` -> `CancellationToken::cancel()` -> worker thread join

Relevant sources:

- `core/service/src/main/java/com/poyka/ripdpi/services/RipDpiVpnService.kt`
- `core/engine/src/main/java/com/poyka/ripdpi/core/TProxyService.kt`
- `native/rust/third_party/hev-socks5-tunnel/crates/hs5t-jni/src/lib.rs`

## Methods Actually Used

| Method | Defined in | Reached from | Current status | Purpose |
| --- | --- | --- | --- | --- |
| `hs5t_config::Config::from_file` | `native/rust/third_party/hev-socks5-tunnel/crates/hs5t-config/src/lib.rs` | `TProxyStartService` | Used | Parses the generated YAML config file. |
| `hs5t_core::run_tunnel` | `native/rust/third_party/hev-socks5-tunnel/crates/hs5t-core/src/lib.rs` | `TProxyStartService` worker thread | Used | Runs the tunnel runtime from the config and Android TUN fd. |
| `CancellationToken::cancel` | `tokio-util` | `TProxyStopService` | Used | Requests tunnel shutdown from another thread. |
| `Stats::snapshot` | `native/rust/third_party/hev-socks5-tunnel/crates/hs5t-core/src/stats.rs` | `TProxyGetStats` | Exposed but currently unused by Kotlin call sites | Returns packet and byte counters. |

## JNI Surface Exposed to Kotlin

`TProxyService.kt` still exposes the same three native methods:

- `TProxyStartService`
- `TProxyStopService`
- `TProxyGetStats`

Compatibility details preserved by the Rust JNI shim:

- `TProxyStartService` still returns `Unit` immediately.
- The Rust bridge owns the worker thread internally, just like the old JNI C layer.
- `TProxyGetStats()` keeps the old array order: `[tx_pkt, tx_bytes, rx_pkt, rx_bytes]`.

## Runtime Dependencies

The old Android C tunnel stack is gone. The Rust tunnel runtime now builds from in-repo crates and links to:

- `libc.so`
- `libdl.so`
- `libm.so`

The Rust crate graph is centered on:

- `hs5t-config`
- `hs5t-core`
- `hs5t-session`
- `hs5t-tunnel`
- `tokio`
- `smoltcp`
- `fast-socks5`
- `serde_yaml`

## Android-specific Notes

- RIPDPI still starts the tunnel with a config file path and an already established Android TUN fd.
- The generated config still points the tunnel to the local SOCKS5 proxy on `127.0.0.1:$port`.
- `libhev-socks5-tunnel.so` therefore still depends on `libripdpi.so` already being active.
