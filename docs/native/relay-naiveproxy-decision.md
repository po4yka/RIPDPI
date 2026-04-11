# NaiveProxy Decision Record

This note closes the Phase P2 roadmap requirement to make a clear go or no-go decision for NaiveProxy based on binary size, maintenance burden, and Android process model.

## Decision

Go with the subprocess model.

NaiveProxy is accepted as a first-class relay kind in RIPDPI, but only as a bundled helper process managed by Android service code. It is intentionally not embedded into `libripdpi.so`, and it does not use a Chromium or JNI-hosted browser stack.

## Why This Model Was Chosen

### Android process model

- RIPDPI already manages native helper binaries with explicit extract/start/health-check/stop lifecycle code.
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/NaiveProxyManager.kt` fits that pattern cleanly and keeps `UpstreamRelaySupervisor` as the single orchestration entry.
- Failure handling stays isolated. If the helper exits or fails readiness, the service can surface the same relay-start failure path used for the other transports.

### Maintenance burden

- The implementation is contained in `native/rust/crates/ripdpi-naiveproxy/src/main.rs`.
- The helper is small and purpose-built: local SOCKS5 listener, upstream TLS session, HTTPS `CONNECT`, optional Basic auth, then byte-for-byte tunneling.
- This avoids carrying a browser engine, Chromium patch set, or Android WebView coupling just to get Naive-compatible upstream behavior.

### Binary size

- Measured host release artifact on April 11, 2026:
  - `native/rust/target/release/ripdpi-naiveproxy`: `2,929,104` bytes
- That footprint is acceptable for a standalone helper binary and materially smaller than any realistic browser-embedding approach.

## Constraints That Stay In Force

- NaiveProxy remains a subprocess backend, not a JNI-embedded transport.
- The helper must keep a narrow contract:
  - listen locally
  - negotiate SOCKS5 with RIPDPI
  - open upstream HTTPS `CONNECT`
  - expose health/readiness clearly to Android service code
- Future feature work should preserve this isolation instead of expanding the helper into a general browser-like runtime.

## Outcome For P2

The roadmap decision requirement is satisfied:

- binary size was measured
- maintenance burden was evaluated against the actual crate layout
- Android process-model fit was validated through `NaiveProxyManager`

Any future work on NaiveProxy should be treated as iterative hardening, not as an open P2 go/no-go question.
