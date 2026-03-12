# byedpi Usage

## Role in RIPDPI

The local SOCKS5 proxy is now implemented by the in-repo Rust module derived from `byedpi`.

- Proxy mode: the app exposes the local SOCKS5 proxy directly.
- VPN mode: the app starts the same local SOCKS5 proxy first, then routes TUN traffic through `hev-socks5-tunnel`.

The built shared library is `libripdpi.so`.

## Diagnostics and Telemetry Role

The same shared library now also carries two additional responsibilities:

- Active diagnostics scans through the linked `ripdpi-monitor` crate
- Passive proxy runtime telemetry for the long-running SOCKS5 listener

The diagnostics path also links the shared `ripdpi-dns-resolver` crate, so encrypted DNS probing and resolver recommendation logic stay in native code rather than Kotlin.

That means `libripdpi.so` is no longer only the proxy engine. It is also the diagnostics entry point used by the Diagnostics screen.

## App Call Chain

### Proxy mode

`RipDpiProxyService.startProxy()` -> `RipDpiProxy.startProxy()` -> `jniCreate(configJson)` -> `jniStart(handle)` -> `ripdpi-android`

### VPN mode

`RipDpiVpnService.startProxy()` -> `RipDpiProxy.startProxy()` -> `jniCreate(configJson)` -> `jniStart(handle)` -> `ripdpi-android`

Relevant sources:

- `core/service/src/main/java/com/poyka/ripdpi/services/RipDpiProxyService.kt`
- `core/service/src/main/java/com/poyka/ripdpi/services/RipDpiVpnService.kt`
- `core/engine/src/main/java/com/poyka/ripdpi/core/RipDpiProxy.kt`
- `core/engine/src/main/java/com/poyka/ripdpi/core/NetworkDiagnostics.kt`
- `native/rust/crates/ripdpi-android/src/lib.rs`
- `native/rust/crates/ripdpi-runtime/src/runtime.rs`
- `native/rust/crates/ripdpi-monitor/src/lib.rs`

## Methods Actually Used

| Method | Defined in | Reached from | When it is used | Purpose |
| --- | --- | --- | --- | --- |
| `ciadpi_config::parse_cli` | `native/rust/third_party/byedpi/crates/ciadpi-config/src/lib.rs` | `jniCreate(configJson)` | Command-line mode only | Parses user-supplied ByeDPI-style arguments into a `RuntimeConfig`. |
| `ciadpi_config::parse_hosts_spec` | `native/rust/third_party/byedpi/crates/ciadpi-config/src/lib.rs` | `jniCreate(configJson)` | UI mode host list setup | Parses the app host list string into normalized host rules. |
| `runtime::create_listener` | `native/rust/crates/ripdpi-runtime/src/runtime.rs` | `jniStart(handle)` | Start only | Opens the local listening socket for the proxy runtime. |
| `process::prepare_embedded` | `native/rust/crates/ripdpi-runtime/src/process.rs` | `jniStart(handle)` | Always before the runtime loop | Resets the embedded shutdown flag without daemon or signal handler behavior. |
| `runtime::run_proxy_with_listener` | `native/rust/crates/ripdpi-runtime/src/runtime.rs` | `jniStart(handle)` | Always after session start | Runs the Rust proxy loop on the listener owned by the native session. |
| `process::request_shutdown` | `native/rust/crates/ripdpi-runtime/src/process.rs` | `jniStop(handle)` | Stop path | Signals the Rust runtime loop to exit. |
| `platform::detect_default_ttl` | `native/rust/crates/ripdpi-runtime/src/platform/mod.rs` | `runtime::run_proxy_with_listener` | When custom TTL is not supplied | Detects the system default TTL before the proxy loop starts. |
| `ripdpi_runtime::install_runtime_telemetry` | `native/rust/crates/ripdpi-runtime/src/lib.rs` | `jniStart(handle)` | Long-running proxy runtime only | Attaches a native observer that records listener lifecycle, accepted clients, route selection, route advances, and native errors. |
| `ripdpi_runtime::clear_runtime_telemetry` | `native/rust/crates/ripdpi-runtime/src/lib.rs` | `jniStart(handle)` unwind | Long-running proxy runtime only | Removes the observer after the proxy loop exits. |
| `MonitorSession::start_scan` | `native/rust/crates/ripdpi-monitor/src/lib.rs` | `NetworkDiagnostics.jniStartScan()` | Diagnostics screen | Starts an active diagnostics session with structured progress and reports. |
| `MonitorSession::poll_progress_json` / `take_report_json` / `poll_passive_events_json` | `native/rust/crates/ripdpi-monitor/src/lib.rs` | `NetworkDiagnostics` JNI methods | Diagnostics screen | Returns scan progress, scan report, and scan-time native events. |

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

## Current RIPDPI-native Strategy Surface

The proxy engine now exposes a substantially broader typed strategy surface than the original Android wrapper around standalone ByeDPI flags.

### Config translation

Android UI mode, diagnostics recommendation drafts, and automatic-probing candidate overlays all pass through the shared `ripdpi-proxy-config` crate before the runtime starts. That keeps the Kotlin UI model, diagnostics monitor, and native runtime aligned around one config shape instead of three loosely matching serializers.

### Markers and chains

The runtime now supports:

- semantic marker offsets such as `host`, `endhost`, `midsld`, `method`, `extlen`, and `sniext`
- adaptive markers such as `auto(balanced)` and `auto(host)` that resolve per payload from live `TCP_INFO`
- ordered TCP and UDP chain steps with per-step activation filters and group activation windows

Notable TCP step kinds now include:

- `hostfake`
- partial Linux/Android-focused `fakedsplit`
- partial Linux/Android-focused `fakeddisorder`

These remain typed RIPDPI steps rather than direct zapret2 parity features.

### Fake payload and fake transport surface

The fake-transport path now includes:

- built-in fake payload profile libraries for HTTP, TLS, UDP, and QUIC Initial traffic
- richer fake TLS mutations (`orig`, `rand`, `rndsni`, `dupsid`, `padencap`, size tuning)
- fixed or adaptive fake TTL for TCP fake sends
- `md5sig`, fake offset markers, and QUIC fake Initial profile selection

`hostfake`, `fakedsplit`, and `fakeddisorder` reuse that same fake-payload and fake-transport pipeline instead of shipping separate blob knobs.

### Runtime adaptation

The shared runtime layer now also adds:

- host autolearn and per-host preferred group promotion
- automatic diagnostics probing with a fixed raw-path candidate suite and manual recommendation output
- activation windows keyed by outbound round, payload size, and stream-byte ranges

Those features are implemented in the native runtime and diagnostics monitor rather than in Kotlin-only glue.

## Implemented Diagnostic Mechanisms

The diagnostics path linked into `libripdpi.so` currently implements:

- `RAW_PATH` and `IN_PATH` scan transports
- UDP DNS integrity checks against encrypted resolvers (DoH/DoT/DNSCrypt)
- HTTPS reachability checks with TLS 1.3 and TLS 1.2 split probing
- HTTP block-page classification
- TCP 16-20 KB cutoff detection with repeated fat-header `HEAD` requests
- Whitelist SNI retry search
- Built-in encrypted resolver sweep and ranking for connectivity scans

Results are returned as typed outcomes and probe details rather than log-line parsing.

## Passive Proxy Runtime Telemetry

While the proxy service is running, `RipDpiProxy.pollTelemetry()` calls `jniPollTelemetry(handle)` and receives a structured snapshot with:

- listener state and bind address
- current active client count
- cumulative session count
- cumulative native error count
- route change count
- last selected desync group
- last target and host observed by the route selector
- a bounded drained event ring

The drained event ring records:
- listener start and stop
- accepted client activity
- client errors
- initial route selection
- route advances caused by reconnect triggers such as connect failure or first-response triggers

## Command-line Mode

`RipDpiProxyCmdPreferences` now serializes a single JSON payload with `kind = "command_line"`.

This path still goes through `ciadpi_config::parse_cli`, so CLI flags are interpreted by the in-repo Rust module rather than an Android-only parser.

## Current Test Coverage

The proxy stack is currently covered by:

- Rust unit, property-based, state-machine, fault-injection, and telemetry-golden tests in `ripdpi-android`
- Rust config and planner coverage for markers, fake payload profiles, fake TLS mutations, activation windows, adaptive split placement, adaptive fake TTL, host autolearn, and fake-step approximations
- repo-owned local-network E2E for the proxy runtime in `ripdpi-runtime`
- Kotlin wrapper and service-layer tests in `core:engine` and `core:service`
- Android instrumentation integration and network E2E through the real `libripdpi.so`
- host-side soak runs for restart loops, sustained traffic, and fault recovery

See [../testing.md](../testing.md) for commands and CI lanes.

## Stop Behavior

Stopping the proxy now does two things in the JNI bridge:

- Calls `process::request_shutdown()`
- Calls `shutdown(listener_fd, SHUT_RDWR)`

The listener is then closed when `runtime::run_proxy_with_listener()` unwinds and drops the native `TcpListener`.
