# byedpi Usage

## Role in RIPDPI

The local SOCKS5 proxy is now implemented by the in-repo Rust module derived from `byedpi`.

- Proxy mode: the app exposes the local SOCKS5 proxy directly.
- VPN mode: the app starts the same local SOCKS5 proxy first, then routes TUN traffic through `hev-socks5-tunnel`.

The built shared library is `libripdpi.so`.

## App Call Chain

### Proxy mode

`RipDpiProxyService.startProxy()` -> `RipDpiProxy.startProxy()` -> `jniCreate(configJson)` -> `jniStart(handle)` -> `ripdpi-android`

### VPN mode

`RipDpiVpnService.startProxy()` -> `RipDpiProxy.startProxy()` -> `jniCreate(configJson)` -> `jniStart(handle)` -> `ripdpi-android`

Relevant sources:

- `core/service/src/main/java/com/poyka/ripdpi/services/RipDpiProxyService.kt`
- `core/service/src/main/java/com/poyka/ripdpi/services/RipDpiVpnService.kt`
- `core/engine/src/main/java/com/poyka/ripdpi/core/RipDpiProxy.kt`
- `native/rust/crates/ripdpi-android/src/lib.rs`
- `native/rust/crates/ripdpi-runtime/src/runtime.rs`

## Methods Actually Used

| Method | Defined in | Reached from | When it is used | Purpose |
| --- | --- | --- | --- | --- |
| `ciadpi_config::parse_cli` | `native/rust/third_party/byedpi/crates/ciadpi-config/src/lib.rs` | `jniCreate(configJson)` | Command-line mode only | Parses user-supplied ByeDPI-style arguments into a `RuntimeConfig`. |
| `ciadpi_config::parse_hosts_spec` | `native/rust/third_party/byedpi/crates/ciadpi-config/src/lib.rs` | `jniCreate(configJson)` | UI mode host list setup | Parses the app host list string into normalized host rules. |
| `runtime::create_listener` | `native/rust/crates/ripdpi-runtime/src/runtime.rs` | `jniCreate(configJson)`, `jniStart(handle)` | Create-time validation and start | Opens the local listening socket for the proxy runtime. |
| `process::prepare_embedded` | `native/rust/crates/ripdpi-runtime/src/process.rs` | `jniStart(handle)` | Always before the runtime loop | Resets the embedded shutdown flag without daemon or signal handler behavior. |
| `runtime::run_proxy_with_listener` | `native/rust/crates/ripdpi-runtime/src/runtime.rs` | `jniStart(handle)` | Always after session start | Runs the Rust proxy loop on the listener owned by the native session. |
| `process::request_shutdown` | `native/rust/crates/ripdpi-runtime/src/process.rs` | `jniStop(handle)` | Stop path | Signals the Rust runtime loop to exit. |
| `platform::detect_default_ttl` | `native/rust/crates/ripdpi-runtime/src/platform/mod.rs` | `runtime::run_proxy_with_listener` | When custom TTL is not supplied | Detects the system default TTL before the proxy loop starts. |

## UI Mode Compatibility

The Android bridge now uses a handle-based session contract instead of exposing raw listener fds to Kotlin.

- `jniCreate(configJson)` validates and stores a native proxy session, then returns an opaque handle.
- `jniStart(handle)` is still blocking.
- `jniStop(handle)` still uses `shutdown(listener_fd, SHUT_RDWR)` to wake the listener and then requests runtime shutdown.
- `jniDestroy(handle)` frees the native session after the blocking start call unwinds.

The wrapper also keeps the previous host-group arrangement used by the Android UI bridge:

- `HostsMode.Whitelist` inserts a host-filter-only group before the main action group.
- `HostsMode.Blacklist` puts the host filter on the main action group.

That behavior matches the old JNI C wrapper, even though the naming comes from the Android settings model.

## Command-line Mode

`RipDpiProxyCmdPreferences` now serializes a single JSON payload with `kind = "command_line"`.

This path still goes through `ciadpi_config::parse_cli`, so CLI flags are interpreted by the in-repo Rust module rather than an Android-only parser.

## Stop Behavior

Stopping the proxy now does two things in the JNI bridge:

- Calls `process::request_shutdown()`
- Calls `shutdown(listener_fd, SHUT_RDWR)`

The listener is then closed when `runtime::run_proxy_with_listener()` unwinds and drops the native `TcpListener`.
