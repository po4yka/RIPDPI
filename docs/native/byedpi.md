# byedpi Usage

## Role in RIPDPI

`byedpi` is the native engine behind the local SOCKS5 proxy. RIPDPI uses it in two cases:

- Proxy mode: the app exposes the local SOCKS5 proxy directly.
- VPN mode: the app starts the same local SOCKS5 proxy first, then routes TUN traffic through `hev-socks5-tunnel`.

The built shared library is `libripdpi.so`.

## App Call Chain

### Proxy mode

`RipDpiProxyService.startProxy()` -> `RipDpiProxy.startProxy()` -> `jniCreateSocket*()` -> `jniStartProxy()` -> `byedpi`

### VPN mode

`RipDpiVpnService.startProxy()` -> `RipDpiProxy.startProxy()` -> `jniCreateSocket*()` -> `jniStartProxy()` -> `byedpi`

Relevant sources:

- `core/service/src/main/java/com/poyka/ripdpi/services/RipDpiProxyService.kt`
- `core/service/src/main/java/com/poyka/ripdpi/services/RipDpiVpnService.kt`
- `core/engine/src/main/java/com/poyka/ripdpi/core/RipDpiProxy.kt`
- `core/engine/src/main/cpp/native-lib.c`

## Methods Actually Used from byedpi

| Method | Defined in | Reached from | When it is used | Purpose |
| --- | --- | --- | --- | --- |
| `listen_socket` | `core/engine/src/main/cpp/byedpi/proxy.c` | `jniCreateSocketWithCommandLine`, `jniCreateSocket` | Always before start | Opens the local listening socket for the SOCKS5 proxy. |
| `event_loop` | `core/engine/src/main/cpp/byedpi/proxy.c` | `jniStartProxy` | Always after socket creation | Runs the main proxy loop until stop or failure. |
| `get_addr` | `core/engine/src/main/cpp/byedpi/main.c` | `jniCreateSocket`; command-line parsing path | UI mode and command-line mode | Parses IP addresses for listen and bind addresses. |
| `get_default_ttl` | `core/engine/src/main/cpp/byedpi/main.c` | `jniCreateSocket`; command-line parsing path | When custom TTL is not supplied | Reads the system default TTL and stores it in `params`. |
| `add` | `core/engine/src/main/cpp/byedpi/main.c` | `jniCreateSocket`; command-line parsing path | Whenever params arrays grow | Allocates and appends `desync_params`, `part`, and `tlsrec` entries. |
| `data_from_str` | `core/engine/src/main/cpp/byedpi/main.c` | `jniCreateSocket` | UI mode host list and inline data setup | Converts inline escaped strings into byte buffers. |
| `parse_hosts` | `core/engine/src/main/cpp/byedpi/main.c` | `jniCreateSocket`; command-line parsing path | Host blacklist or whitelist setup | Parses host lists into the internal memory pool structure. |
| `change_tls_sni` | `core/engine/src/main/cpp/byedpi/packets.c` | `jniCreateSocket`; command-line parsing path | Fake TLS desync mode | Rewrites SNI inside the bundled fake TLS ClientHello. |
| `mem_pool` | `core/engine/src/main/cpp/byedpi/mpool.c` | `jniCreateSocket`; command-line parsing path | After params are prepared | Creates the memory pool used by host matching and other cached data. |
| `clear_params` | `core/engine/src/main/cpp/byedpi/main.c` | `reset_params` and error paths | Stop and cleanup paths | Frees allocated config state before restoring defaults. |
| `ftob` | `core/engine/src/main/cpp/byedpi/main.c` | `parse_args` in `utils.c` | Command-line mode only | Reads file-backed fake data, IP options, or host lists. |
| `parse_offset` | `core/engine/src/main/cpp/byedpi/main.c` | `parse_args` in `utils.c` | Command-line mode only | Parses split and TLS record offsets from CLI flags. |

## Wrapper-local Helpers

These are part of RIPDPI's JNI bridge, not upstream `byedpi` public API:

- `parse_args` in `core/engine/src/main/cpp/utils.c`
- `reset_params` in `core/engine/src/main/cpp/utils.c`

`parse_args` mirrors the upstream CLI parsing style and then uses `byedpi` globals and helper functions such as `get_addr`, `add`, `ftob`, `parse_hosts`, `parse_offset`, `get_default_ttl`, and `mem_pool`.

## UI Mode vs Command-line Mode

### UI mode

`RipDpiProxyUIPreferences` maps app settings directly into `jniCreateSocket(...)`.

Typical dependency methods reached in this path:

- `get_addr`
- `get_default_ttl`
- `add`
- `data_from_str`
- `parse_hosts`
- `change_tls_sni`
- `mem_pool`
- `listen_socket`
- `event_loop`
- `clear_params`

### Command-line mode

`RipDpiProxyCmdPreferences` switches `RipDpiProxy` to `jniCreateSocketWithCommandLine(...)`.

This path adds CLI-specific parsing work through `parse_args`, which can also reach:

- `ftob`
- `parse_offset`

Command-line mode is enabled from `RipDpiProxyPreferences.fromSettingsStore()` when `settings.enableCmdSettings` is true.

## Stop Behavior

Stopping the proxy does not call a dedicated upstream `byedpi` stop function. RIPDPI does this instead:

- Calls `shutdown(fd, SHUT_RDWR)` in the JNI wrapper.
- Calls `reset_params()`, which delegates cleanup to `clear_params()`.
- Lets `event_loop()` exit once the listening socket and active connections unwind.

## Internal byedpi Logic Not Called Directly by App

The app never calls these methods directly, but they execute under `event_loop()` as part of normal proxy operation:

- `on_accept`
- `on_request`
- `on_tunnel`
- `on_desync`
- `desync`
- `desync_udp`

Those are internal `byedpi` runtime details, not app-facing entrypoints.
