# Proxy Engine

## Role in RIPDPI

The local SOCKS5 proxy is implemented by the in-repo Rust native module.

- Proxy mode: the app exposes the local SOCKS5 proxy directly.
- VPN mode: the app starts the same local SOCKS5 proxy first, then routes TUN traffic through the TUN-to-SOCKS tunnel.

The built shared library is `libripdpi.so`.

## Diagnostics and Telemetry Role

The same shared library now also carries two additional responsibilities:

- Active diagnostics scans through the linked `ripdpi-monitor` crate
- Passive proxy runtime telemetry for the long-running SOCKS5 listener

The diagnostics path also links the shared `ripdpi-dns-resolver` crate, so encrypted DNS probing and resolver recommendation logic stay in native code rather than Kotlin.

That means `libripdpi.so` is no longer only the proxy engine. It is also the diagnostics entry point used by the Diagnostics screen.

## Call Chains

### Desktop CLI (macOS/Linux)

`main()` -> `ripdpi_config::parse_cli(args)` -> `ProcessGuard::prepare()` -> `runtime::run_proxy(config)`

The CLI binary (`ripdpi`) wraps the same `ripdpi-runtime` and `ripdpi-config` used by Android, with no JNI. Signal handling (SIGINT/SIGTERM/SIGHUP) uses the existing `ProcessGuard`. Telemetry is emitted via `tracing` to stderr.

```bash
cargo run -p ripdpi-cli -- -p 1080 -x 1      # info logging
RUST_LOG=debug cargo run -p ripdpi-cli       # override via env
```

Relevant sources:

- `native/rust/crates/ripdpi-cli/src/main.rs`
- `native/rust/crates/ripdpi-cli/src/telemetry.rs`

### Android Proxy mode

`RipDpiProxyService.startProxy()` -> `ConnectionPolicyResolver.resolve()` -> `RipDpiProxy.startProxy()` -> `jniCreate(configJson)` -> `jniStart(handle)` -> `runtime::create_listener()` -> `runtime::run_proxy_with_embedded_control()`

### Android VPN mode

`RipDpiVpnService.startProxy()` -> `ConnectionPolicyResolver.resolve()` -> `RipDpiProxy.startProxy()` -> `jniCreate(configJson)` -> `jniStart(handle)` -> `runtime::create_listener()` -> `runtime::run_proxy_with_embedded_control()`

Relevant sources:

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/RipDpiProxyService.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/RipDpiVpnService.kt`
- `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxy.kt`
- `core/engine/src/main/kotlin/com/poyka/ripdpi/core/NetworkDiagnostics.kt`
- `native/rust/crates/ripdpi-android/src/lib.rs`
- `native/rust/crates/ripdpi-runtime/src/runtime.rs`
- `native/rust/crates/ripdpi-monitor/src/lib.rs`

## Methods Actually Used

| Method | Defined in | Reached from | When it is used | Purpose |
| --- | --- | --- | --- | --- |
| `ripdpi_config::parse_cli` | `native/rust/crates/ripdpi-config/src/lib.rs` | `jniCreate(configJson)` | Command-line mode only | Parses user-supplied CLI arguments into a `RuntimeConfig`. |
| `ripdpi_config::parse_hosts_spec` | `native/rust/crates/ripdpi-config/src/lib.rs` | `jniCreate(configJson)` | UI mode host list setup | Parses the app host list string into normalized host rules. |
| `runtime::create_listener` | `native/rust/crates/ripdpi-runtime/src/runtime.rs` | `jniStart(handle)` | Start only | Opens the local listening socket for the proxy runtime. |
| `runtime::run_proxy_with_embedded_control` | `native/rust/crates/ripdpi-runtime/src/runtime.rs` | `jniStart(handle)` | Always after session start | Runs the Rust proxy loop on the listener owned by the native session, with session-local shutdown state, telemetry sink, and runtime context. |
| `EmbeddedProxyControl::request_shutdown` | `native/rust/crates/ripdpi-runtime/src/lib.rs` | `jniStop(handle)` | Stop path | Signals the active embedded proxy session to exit without relying on standalone daemon/process control. |
| `platform::detect_default_ttl` | `native/rust/crates/ripdpi-runtime/src/platform/mod.rs` | `runtime::run_proxy_with_embedded_control` | When custom TTL is not supplied | Detects the system default TTL before the proxy loop starts. |
| `MonitorSession::start_scan` | `native/rust/crates/ripdpi-monitor/src/lib.rs` | `NetworkDiagnostics.jniStartScan()` | Diagnostics screen | Starts an active diagnostics session with structured phase progress, live strategy-candidate progress, and reports. |
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

The proxy engine exposes a broad typed strategy surface.

### Config translation

Android UI mode, diagnostics recommendation drafts, and automatic-probing candidate overlays all pass through the shared `ripdpi-proxy-config` crate before the runtime starts. That keeps the Kotlin UI model, diagnostics monitor, and native runtime aligned around one config shape instead of three loosely matching serializers.

The same JSON path is also used to replay validated remembered network policies. `RipDpiProxyJsonPreferences` can apply an exact normalized `proxyConfigJson` with a fresh `networkScopeKey` and runtime context, instead of rebuilding the policy from today's UI settings.

### Markers and chains

The runtime now supports:

- semantic marker offsets such as `host`, `endhost`, `midsld`, `method`, `extlen`, and `sniext`
- adaptive markers such as `auto(balanced)` and `auto(host)` that resolve per payload from live `TCP_INFO`
- ordered TCP and UDP chain steps with per-step activation filters and group activation windows
- grouped `multidisorder` TCP runs where each contiguous terminal step contributes one marker and the runtime sends the resulting regions in reverse order

Notable TCP step kinds now include:

- `hostfake`
- Linux/Android-focused `multidisorder` (manual-chain only in v1)
- partial Linux/Android-focused `fakedsplit`
- partial Linux/Android-focused `fakeddisorder`

These are typed RIPDPI steps.

Notable UDP step kinds for QUIC DPI evasion:

- `DummyPrepend` -- random UDP datagram before QUIC Initial to reset GFW flow state
- `QuicSniSplit` -- re-encrypt Initial with ClientHello split across CRYPTO frames
- `QuicFakeVersion` -- replace QUIC version field to prevent DPI decryption

### Fake payload and fake transport surface

The fake-transport path now includes:

- built-in fake payload profile libraries for HTTP, TLS, UDP, and QUIC Initial traffic
- richer fake TLS mutations (`orig`, `rand`, `rndsni`, `dupsid`, `padencap`, size tuning)
- fixed or adaptive fake TTL for TCP fake sends
- `md5sig`, fake offset markers, and QUIC fake Initial profile selection
- TCP window clamping (`TCP_WINDOW_CLAMP`) to force small server response segments
- QUIC source port binding to evade port-based GFW filtering

`hostfake`, `fakedsplit`, and `fakeddisorder` reuse that same fake-payload and fake-transport pipeline instead of shipping separate blob knobs. `multidisorder` is different: it uses packet-owned TCP repair plus raw IPv4/IPv6 injection to emit the real payload segments in reverse order, then hands the live stream off to a repaired replacement socket.

### Runtime adaptation

The shared runtime layer now also adds:

- Geneva-style strategy evolution (`StrategyEvolver`) with epsilon-greedy + UCB1 selection across combo dimensions
- host autolearn and per-host preferred group promotion scoped by `networkScopeKey`
- validated remembered-network policy replay with hashed network fingerprints and optional VPN DNS override
- automatic diagnostics probing plus `full_matrix_v1` audit runs with rotating curated target cohorts, hidden handover-triggered `quick_v1` probes, and manual recommendation output
- separate TCP, QUIC, and DNS strategy-family labels for scoring and diagnostics
- activation windows keyed by outbound round, payload size, and stream-byte ranges
- retry-stealth pacing and seeded candidate diversification in both live runtime retries and diagnostics probes

### Network-aware policy resolution

Before the native runtime starts, the Android service layer now resolves policy against the current network:

- `NetworkFingerprintProvider` captures a hashable network identity from transport, validation/captive state, private DNS mode, DNS servers, and Wi-Fi or cellular identity tuples.
- `ConnectionPolicyResolver` can auto-apply a validated remembered policy for the current `fingerprintHash`, including exact `proxyConfigJson` and VPN-only DNS override replay.
- `ActiveConnectionPolicyStore` tracks the active policy, its `policySignature`, `fingerprintHash`, and whether it was applied from remembered policy memory.
- `NetworkHandoverMonitor` re-runs the same resolver on actionable handovers and forces a full runtime restart even when the signature stays the same, so sockets and resolver state are rebound to the new path.

The packet-level pieces live in the native runtime and diagnostics monitor, while Kotlin owns policy resolution, remembered-policy replay, and handover-triggered restart orchestration.

## Implemented Diagnostic Mechanisms

The diagnostics path linked into `libripdpi.so` currently implements:

- `RAW_PATH` and `IN_PATH` scan transports
- Strategy-probe suites for fast `quick_v1` recommendations and `full_matrix_v1` automatic audit runs
- Candidate-aware progress for strategy-probe runs, including active TCP/QUIC lane plus candidate index/total and label
- UDP DNS integrity checks against encrypted resolvers (DoH/DoT/DNSCrypt/DoQ)
- HTTPS reachability checks with TLS 1.3 and TLS 1.2 split probing
- HTTP block-page classification
- TCP 16-20 KB cutoff detection with repeated fat-header `HEAD` requests
- Whitelist SNI retry search
- Built-in encrypted resolver sweep and ranking for connectivity scans with diversified DoH/DoT/DNSCrypt path candidates and bootstrap validation
- Rotating curated target cohorts for `automatic-audit`, with selected cohort provenance persisted into the request/report path
- Full-matrix audit assessment with confidence, matrix coverage, winner coverage, and stable warnings

Results are returned as typed outcomes and probe details rather than log-line parsing.

## Passive Proxy Runtime Telemetry

While the proxy service is running, `RipDpiProxy.pollTelemetry()` calls `jniPollTelemetry(handle)` and receives a structured snapshot with:

- listener state and bind address
- current active client count
- cumulative session count
- cumulative native error count
- route change count
- retry pacing count, last retry reason, and last retry backoff
- candidate diversification count
- last selected desync group
- last target and host observed by the route selector
- host-autolearn enabled state plus learned/penalized host counts
- last failure class and fallback action
- a bounded drained event ring

The drained event ring records:
- listener start and stop
- accepted client activity
- client errors
- initial route selection
- route advances caused by reconnect triggers such as connect failure or first-response triggers
- retry pacing decisions and candidate-order diversification events

## Command-line Mode

`RipDpiProxyCmdPreferences` now serializes a single JSON payload with `kind = "command_line"`.

This path still goes through `ripdpi_config::parse_cli`, so CLI flags are interpreted by the in-repo Rust module.

## Current Test Coverage

The proxy stack is currently covered by:

- Rust unit, property-based, state-machine, fault-injection, and telemetry-golden tests in `ripdpi-android`
- Rust config and planner coverage for markers, fake payload profiles, fake TLS mutations, activation windows, adaptive split placement, adaptive fake TTL, adaptive tuning beyond TTL, host autolearn scoping, retry stealth, and fake-step approximations
- repo-owned local-network E2E for the proxy runtime in `ripdpi-runtime`
- Kotlin wrapper and service-layer tests in `core:engine` and `core:service`, including network-memory resolution and handover-triggered restart behavior
- Android instrumentation integration and network E2E through the real `libripdpi.so`
- host-side soak runs for restart loops, sustained traffic, and fault recovery

See [../testing.md](../testing.md) for commands and CI lanes.

## Stop Behavior

Stopping the proxy now does two things in the JNI bridge:

- Calls `EmbeddedProxyControl::request_shutdown()`
- Calls `shutdown(listener_fd, SHUT_RDWR)`

The listener is then closed when `runtime::run_proxy_with_embedded_control()` unwinds and drops the native `TcpListener`.
