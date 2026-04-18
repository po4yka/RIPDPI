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

The current implementation goes beyond the original go or no-go decision:

- helper version probing before launch
- readiness handshake through explicit `RIPDPI-READY` signaling
- structured failure signaling through `RIPDPI-ERROR`
- Android-side classification for DNS, TLS, HTTP `CONNECT`, and auth failures
- credential-redacted surfaced logs and error text
- graceful stop handling
- bounded watchdog restarts for unexpected helper exits

This means NaiveProxy is no longer a provisional transport. It is a supported runtime with explicit operational boundaries.

## Boundaries

These constraints remain intentional:

- NaiveProxy stays a subprocess backend, not a JNI-embedded transport.
- The helper should remain small and protocol-focused.
- Future work should improve observability and compatibility, not expand the helper toward browser-engine behavior.

## Remaining Work

The remaining work is operational validation rather than design uncertainty:

- broader field validation across upstream proxies and auth combinations
- watchdog tuning based on real helper failure patterns
- continued telemetry review to keep surfaced errors useful without leaking secrets

## Compatibility Matrix

The current repo-owned validation and classification matrix is:

| Surface | Current handling |
| --- | --- |
| Basic auth over HTTPS `CONNECT` | supported; credentials are redacted from surfaced logs |
| Custom `X-Naive-Path` header | supported when configured |
| Invalid upstream server name / TLS certificate failure | classified as `tls`; watchdog does not restart |
| Upstream `407` or `Proxy-Authorization` failure | classified as `auth`; watchdog does not restart |
| Upstream non-auth `CONNECT` rejection | classified as `http_connect`; watchdog does not restart |
| DNS resolution failure | classified as `dns`; watchdog retries with slower backoff |
| TCP connect/reset/timeout failures | classified as `connect`; watchdog retries |
| Unstructured helper crash or generic runtime error | classified as `runtime` or `helper_exit`; watchdog retries within budget |

## Watchdog Policy

NaiveProxy restarts are now reason-aware instead of unconditional.

Retryable classes:

- `dns`
- `connect`
- `runtime`
- `helper_exit`

Terminal classes:

- `auth`
- `http_connect`
- `tls`
- `config`

This keeps the helper responsive to transient path failures without looping on
deterministic credential or TLS configuration problems.
