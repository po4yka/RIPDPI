# Change: Correct QUIC Initial and VN template diagnostics

Task ID: `DGN-1794744778962822`

## Why

The DPI probe suite can report filtering when a datagram is lost or when a local check fails. It can miss a correlated reply and report a Retry as a successful Initial. Browser names make the result appear to apply to browser traffic.

## What Changes

- Record separate QUIC first-flight replies and receive correlated packets within one bounded exchange.
- Compare templates on one selected peer and preserve each attempt's outcome.
- Use neutral results for missing evidence and remove QUIC template loss from the DPI verdict.
- Validate QUIC targets before probing and accept complete IPv6 literals.
- Identify synthetic templates and keep HTTP/3 validation in the separate HTTP/3 diagnostic.

## Capabilities

### New Capabilities

- `quic-template-diagnostics` covers outgoing QUIC v1 Initial and reserved-version probes in the DPI probe suite.

### Modified Capabilities

None.

## Impact

Kotlin suite execution and presentation, Rust packet validation, and the paired JNI result contract. The Android app and its native library must use the same build revision.
