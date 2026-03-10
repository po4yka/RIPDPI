# hev-socks5-tunnel Usage

## Role in RIPDPI

`hev-socks5-tunnel` is used only in VPN mode. It takes the Android TUN file descriptor, reads packets from it, and forwards traffic to the local SOCKS5 proxy started by `byedpi`.

The built shared library is `libhev-socks5-tunnel.so`.

## App Call Chain

`RipDpiVpnService.startTun2Socks()` -> `TProxyService.TProxyStartService(configPath, tunFd)` -> JNI `native_start_service()` -> native worker thread -> `hev_socks5_tunnel_main(configPath, tunFd)`

Stop path:

`RipDpiVpnService.stopTun2Socks()` -> `TProxyService.TProxyStopService()` -> JNI `native_stop_service()` -> `hev_socks5_tunnel_quit()`

The relevant sources are:

- `core/service/src/main/java/com/poyka/ripdpi/services/RipDpiVpnService.kt`
- `core/engine/src/main/java/com/poyka/ripdpi/core/TProxyService.kt`
- `core/engine/src/main/jni/hev-socks5-tunnel/src/hev-jni.c`
- `core/engine/src/main/jni/hev-socks5-tunnel/src/hev-main.c`

## Methods Actually Used from hev-socks5-tunnel

| Method | Defined in | Reached from | Current status | Purpose |
| --- | --- | --- | --- | --- |
| `hev_socks5_tunnel_main` | `core/engine/src/main/jni/hev-socks5-tunnel/src/hev-main.c` | `native_start_service` worker thread | Used | Starts the tunnel runtime from a YAML config file and a TUN fd. |
| `hev_socks5_tunnel_quit` | `core/engine/src/main/jni/hev-socks5-tunnel/src/hev-main.c` | `native_stop_service` | Used | Stops the running tunnel. |
| `hev_socks5_tunnel_stats` | `core/engine/src/main/jni/hev-socks5-tunnel/src/hev-socks5-tunnel.c` | `native_get_stats` | Exposed but currently unused by Kotlin call sites | Returns packet and byte counters. |

## JNI Surface Exposed to Kotlin

`TProxyService.kt` exposes three native methods:

- `TProxyStartService`
- `TProxyStopService`
- `TProxyGetStats`

Only the first two are currently called from Kotlin service code. I did not find any current call site for `TProxyGetStats()`.

## What Happens Inside `hev_socks5_tunnel_main`

`hev_socks5_tunnel_main()` is the single runtime entrypoint the app actually uses, but it fans out into several internal and transitive native components:

1. `hev_config_init_from_file(config_path)`
2. `hev_config_get_misc_*()` accessors
3. `hev_task_system_init()`
4. `lwip_init()`
5. `hev_socks5_tunnel_init(tun_fd)`
6. `hev_socks5_tunnel_run()`
7. Cleanup:
   - `hev_socks5_tunnel_fini()`
   - `hev_socks5_logger_fini()`
   - `hev_logger_fini()`
   - `hev_config_fini()`
   - `hev_task_system_fini()`

This means the app directly calls only `hev_socks5_tunnel_main()` and `hev_socks5_tunnel_quit()`, while the rest of the native tunnel stack is entered transitively from that main runtime function.

## Transitive Native Dependencies Used Through `hev-socks5-tunnel`

### `yaml`

Used for parsing the generated tunnel config file through `hev_config_init_from_file()`.

### `lwip`

Initialized through `lwip_init()` and used as the embedded network stack inside the tunnel runtime.

### `hev-task-system`

Initialized through `hev_task_system_init()` and used for coroutine-style scheduling inside the tunnel.

## Android-specific Notes

- RIPDPI always starts `hev-socks5-tunnel` with a config file path and an already established Android TUN fd.
- The TUN fd is created in `RipDpiVpnService.createBuilder(...).establish()`.
- The generated config points `hev-socks5-tunnel` to the local SOCKS5 proxy on `127.0.0.1:$port`, so this library depends on `byedpi` already running.

## Not Used by the Android Build

The vendored `wintun` support under `third-part/wintun` is Windows-only and is not part of the Android build path used by RIPDPI.
