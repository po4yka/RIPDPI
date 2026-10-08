# Change: Scope diagnosis controls to measured protocols

Task ID: `DGN-1791484491005718`

## Why

A successful TLS control currently validates unrelated DNS, QUIC, HTTP and throughput findings. Some summaries attribute incomplete measurements to blocking.

## What Changes

- Match control validation to the executed protocol and preserve unknown controls.
- Remove generic TLS plus QUIC failure as evidence of SNI interference.
- Describe ECH, QUIC, throughput and advertised HTTP/3 observations without provider attribution.

## Capabilities

### New Capabilities

- `diagnosis-protocol-evidence`: bounded interpretation of existing observations.

### Modified Capabilities

- None.

## Impact

Kotlin diagnostics and native diagnosis classification. Existing diagnosis codes remain compatible. No schema or dependency changes.
