# NaiveProxy Runtime

RIPDPI ships NaiveProxy as a managed helper runtime, not as a JNI-embedded transport.

This file keeps the current-state rationale and the operational boundaries for that choice.

## Runtime Model

NaiveProxy runs as a bundled subprocess started by Android service code.

Key pieces:

- `core/service/src/main/kotlin/com/poyka/ripdpi/services/NaiveProxyManager.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/SubprocessSocksRelayManager.kt`
- `native/rust/crates/ripdpi-naiveproxy/src/main.rs`

The helper contract is intentionally narrow:

- bind a local SOCKS5 listener
- negotiate SOCKS5 with RIPDPI
- open the upstream HTTPS `CONNECT`
- tunnel bytes after the upstream proxy is established
- report readiness and structured failures back to Android service code

## Why This Model Stays

### Android lifecycle fit

RIPDPI already manages standalone helper binaries for transport and privileged workflows. NaiveProxy fits that lifecycle without adding a new embedding or browser runtime model.

### Isolation

Helper crashes, startup failures, and upstream auth or TLS errors stay isolated from `libripdpi.so` and are surfaced through the same relay-start and unexpected-exit paths used by other managed upstreams.

### Binary scope

The in-repo helper remains much smaller and easier to reason about than a Chromium-derived or WebView-based approach.

## Current Behavior

The current implementation includes:

- a mandatory `--probe` preflight before every helper launch and watchdog restart
- schema validation of the `RIPDPI-PROBE` capability line before the main process starts
- readiness handshake through explicit `RIPDPI-READY` signaling
- structured failure signaling through `RIPDPI-ERROR`. An error before `RIPDPI-READY` fails the start. An error after it belongs to one client stream: it shows in telemetry but does not stop a crashed helper from restarting, and the helper prints `RIPDPI-UPSTREAM-OK|naiveproxy|` on the first successful upstream open after start or after a failure, which clears it. Relay health is `unknown` until that line, then `healthy`, and `degraded` while a stream failure is the latest report
- a configured outbound bind IP is refused before launch, because the helper cannot bind a source address
- Android-side classification for DNS, TLS, HTTP `CONNECT`, and auth failures
- credential-redacted surfaced logs and error text
- graceful stop handling
- bounded watchdog restarts for unexpected helper exits

This means NaiveProxy is no longer a provisional transport. It is a supported runtime with explicit operational boundaries.

The manager accepts probe schema `1` only. Missing probe support, malformed output, a non-zero probe exit, timeout, or any other schema version rejects startup before the main helper process is launched. A successful probe hands the exact extracted file to the main launch path, so the checked artifact cannot be replaced by a second extraction. The bundled helper is extracted afresh from the APK on every start, so schema `0` fallback is intentionally not supported: accepting a helper without `--probe` would hide packaging drift rather than preserve a legitimate installed runtime.

Probe rejection is a typed relay-configuration startup failure. Relay telemetry records `lastFailureClass = relay_compatibility` with a fixed, credential-free explanation, keeping helper incompatibility distinct from a subprocess crash. The existing `--version`, `RIPDPI-READY`, and `RIPDPI-ERROR` paths remain unchanged after a successful preflight.

## Boundaries

These constraints remain intentional:

- NaiveProxy stays a subprocess backend, not a JNI-embedded transport.
- The helper should remain small and protocol-focused.
- New work should improve observability and compatibility, not expand the helper toward browser-engine behavior.

## Remaining Work

The remaining work is validation outside this repository's local lanes:

- Android device evidence for the routed path: protected sockets, protected-DoH bootstrap during VPN start and network handover, and no direct target egress
- capture-based comparison with the Chromium client (preamble, RST_STREAM padding, flow-control windows) before any traffic-parity decision
- field validation across upstream servers and auth combinations

## Compatibility Matrix

| Surface | Current handling |
| --- | --- |
| Helper capability schema | `--probe` is required before each launch; only schema `1` is accepted |
| Upstream server | checked against klzgrad/forwardproxy `v2.11.7-naive2` on patched Caddy `v2.11.7` by the `independent-naiveproxy-*` acceptance scenarios |
| Basic auth over HTTP/2 `CONNECT` | supported; credentials are redacted from surfaced logs |
| Custom `X-Naive-Path` header | sent when configured; ignored by the pinned forwardproxy server |
| Connection use | tunnels share one pooled HTTP/2 connection; 10 min age and 5 min idle retirement |
| Server hostname under VPN | protected DoH, never system DNS |
| TLS certificate or ALPN failure | classified as `tls` |
| Upstream `407` | classified as `auth` |
| Other non-200 `CONNECT` reply | classified as `http_connect` |
| DNS resolution failure | classified as `dns` |
| TCP connect/reset/timeout or socket permission failure | classified as `connect` |

## Watchdog Policy

Failure classes describe single client streams: the helper keeps serving after any of them, and they show in relay telemetry until `RIPDPI-UPSTREAM-OK` clears them. They do not decide restarts. A startup failure throws out of `start()` and ends the relay. A helper that exits after startup is restarted with the same bounded budget (3 attempts in 60 seconds), whatever its last stream failure was.
