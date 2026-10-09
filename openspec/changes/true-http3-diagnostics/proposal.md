# Change: Validate real HTTP3 diagnostics

Task ID: `DGN-1791532838740620`

## Why

A QUIC Initial reply or Alt-Svc header does not prove that an HTTP/3 request works. Users need separate handshake, HTTP response and body progress evidence.

## What Changes

- Add an opt-in manual raw-path HTTP/3 profile and full-analysis stage.
- Perform a verified QUIC/TLS handshake with ALPN h3, send a GET request, and read a bounded response without TCP fallback.
- Preserve independent protocol validation, HTTP status, transfer progress and failure stage in history, connection stages and redacted exports.
- Keep QUIC Initial measurements distinct and correct their fingerprint label.
- No breaking storage or JNI changes.

## Capabilities

### New Capabilities

- `true-http3-diagnostics`: real bounded HTTP/3 request and response evidence.

### Modified Capabilities

- None.

## Impact

Native diagnostics contracts, transport, HTTP, runner, monitor and probe registries; Kotlin diagnostics/catalog; app UI and ten locales; shared contract fixtures. Reuse existing workspace QUIC and HTTP/3 packages without version changes.
