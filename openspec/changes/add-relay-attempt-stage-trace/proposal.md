# Change: Add privacy-safe relay attempt stage trace

Task ID: `DGN-1786592449526581`

## Why

Current runtime diagnostics can report that a VLESS Reality relay failed with a
socket reset, but cannot establish whether the peer closed during TCP connect,
Reality TLS, the VLESS request, response validation, or downstream SOCKS
egress. This prevents a diagnostic archive from distinguishing configuration
incompatibility, relay rejection, path interference, and a local VPN-chain
failure.

## What Changes

- Record a bounded, ordered, privacy-safe stage trace for each VLESS Reality TCP
  relay attempt.
- Correlate native relay stages with the owning runtime and connection session
  using opaque identifiers.
- Persist and export structured stage outcomes, timings, and typed failures in
  the diagnostic archive, including partial traces from failed attempts.
- Preserve unknown or unavailable evidence without converting it into a causal
  verdict.
- Keep collection off per-packet and per-byte paths and exclude credentials,
  raw endpoints, handshake bytes, and payloads.
- BREAKING: none. Runtime telemetry additions are optional and defaulted; older
  producers and consumers retain their existing behavior.

## Capabilities

### New Capabilities

- `relay-attempt-stage-trace`: Defines bounded per-attempt protocol-stage
  evidence and its privacy-safe export contract.

### Modified Capabilities

- `harden-remaining-diagnostics-evidence`: Diagnostic archives preserve and
  qualify correlated runtime relay evidence instead of exposing only a final
  free-form error.

## Impact

- Native Rust: `ripdpi-vless`, `ripdpi-relay-core`, `ripdpi-relay-android`,
  `android-support`, and `ripdpi-android-telemetry-adapter`.
- Kotlin: runtime telemetry models and decoders, service telemetry projection,
  diagnostics persistence, redaction, and archive rendering.
- Contracts: additive runtime telemetry fields, diagnostics Room migration,
  and diagnostic archive schema and fixtures; no JNI method or protobuf change.
