# byedpi Usage

## Role in RIPDPI

The local SOCKS5 proxy is now implemented by the in-repo Rust module derived from `byedpi`.

- Proxy mode: the app exposes the local SOCKS5 proxy directly.
- VPN mode: the app starts the same local SOCKS5 proxy first, then routes TUN traffic through `hev-socks5-tunnel`.

The built shared library is `libripdpi.so`.

## App Call Chain

### Proxy mode

`RipDpiProxyService.startProxy()` -> `RipDpiProxy.startProxy()` -> `jniCreateSocket*()` -> `jniStartProxy()` -> `ciadpi-jni`

### VPN mode

`RipDpiVpnService.startProxy()` -> `RipDpiProxy.startProxy()` -> `jniCreateSocket*()` -> `jniStartProxy()` -> `ciadpi-jni`

Relevant sources:

- `core/service/src/main/java/com/poyka/ripdpi/services/RipDpiProxyService.kt`
- `core/service/src/main/java/com/poyka/ripdpi/services/RipDpiVpnService.kt`
- `core/engine/src/main/java/com/poyka/ripdpi/core/RipDpiProxy.kt`
- `native/rust/third_party/byedpi/crates/ciadpi-jni/src/lib.rs`

## Methods Actually Used

| Method | Defined in | Reached from | When it is used | Purpose |
| --- | --- | --- | --- | --- |
| `ciadpi_config::parse_cli` | `native/rust/third_party/byedpi/crates/ciadpi-config/src/lib.rs` | `jniCreateSocketWithCommandLine` | Command-line mode only | Parses user-supplied ByeDPI-style arguments into a `RuntimeConfig`. |
| `ciadpi_config::parse_hosts_spec` | `native/rust/third_party/byedpi/crates/ciadpi-config/src/lib.rs` | `jniCreateSocket` | UI mode host list setup | Parses the app host list string into normalized host rules. |
| `runtime::create_listener` | `native/rust/third_party/byedpi/crates/ciadpi-bin/src/runtime.rs` | `jniCreateSocketWithCommandLine`, `jniCreateSocket` | Always before start | Opens the local listening socket and returns the raw fd to Kotlin. |
| `process::prepare_embedded` | `native/rust/third_party/byedpi/crates/ciadpi-bin/src/process.rs` | `jniStartProxy` | Always before the runtime loop | Resets the embedded shutdown flag without daemon or signal handler behavior. |
| `runtime::run_proxy_with_listener` | `native/rust/third_party/byedpi/crates/ciadpi-bin/src/runtime.rs` | `jniStartProxy` | Always after socket creation | Runs the Rust proxy loop on the listener fd supplied by Kotlin. |
| `process::request_shutdown` | `native/rust/third_party/byedpi/crates/ciadpi-bin/src/process.rs` | `jniStopProxy` | Stop path | Signals the Rust runtime loop to exit. |
| `platform::detect_default_ttl` | `native/rust/third_party/byedpi/crates/ciadpi-bin/src/platform/mod.rs` | `runtime::run_proxy_with_listener` | When custom TTL is not supplied | Detects the system default TTL before the proxy loop starts. |

## UI Mode Compatibility

The JNI wrapper intentionally preserves the old Android UI bridge contract instead of exposing the Rust CLI directly.

- `jniCreateSocket(...)` still returns a raw listener fd before the proxy loop starts.
- `jniStartProxy(fd)` is still blocking.
- `jniStopProxy(fd)` still uses `shutdown(fd, SHUT_RDWR)` to wake the listener and then requests runtime shutdown.

The wrapper also keeps the previous host-group arrangement used by the Android UI bridge:

- `HostsMode.Whitelist` inserts a host-filter-only group before the main action group.
- `HostsMode.Blacklist` puts the host filter on the main action group.

That behavior matches the old JNI C wrapper, even though the naming comes from the Android settings model.

## Command-line Mode

`RipDpiProxyCmdPreferences` still switches `RipDpiProxy` to `jniCreateSocketWithCommandLine(...)`.

This path now goes through the Rust parser in `ciadpi_config::parse_cli`, so CLI flags are interpreted by the in-repo Rust module instead of the deleted `utils.c` parser.

## Stop Behavior

Stopping the proxy now does two things in the JNI bridge:

- Calls `process::request_shutdown()`
- Calls `shutdown(fd, SHUT_RDWR)`

The listener fd is then closed when `runtime::run_proxy_with_listener()` unwinds and drops the reconstructed `TcpListener`.
