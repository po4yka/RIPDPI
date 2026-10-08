# Change: Preserve TLS probe failure stages and retry evidence

Task ID: `DGN-1791481942929169`

## Why

A failed TCP connection can become a TLS ClientHello diagnosis. A successful retry retains errors from the previous attempt and can still produce a blocking diagnosis.

## What Changes

- Require measured handshake-stage evidence for TLS handshake diagnoses.
- Update the successful attempt and aggregate facts together after a retry.

## Capabilities

### New Capabilities

- `diagnostics-tls-evidence`: accurate stage and retry evidence for domain probes.

### Modified Capabilities

- None.

## Impact

- Rust diagnostics runner and classification crates. No schema, dependency, or public API change.
