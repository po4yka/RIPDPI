# Change: Correct native contract and configuration defects

Task ID: `RST-1790419862638443`

## Why

The audit found accepted invalid configuration, incomplete HTTP responses, an unauthenticated proxy path, and incorrect diagnostic or transport data. These defects can cause silent policy changes, incorrect results, or exposed proxy access.

## What Changes

- Reject invalid or ambiguous native configuration and preserve explicit validation errors. BREAKING: previously accepted invalid values fail during parsing.
- Require authentication on public proxy listeners and reject SOCKS4 when token authentication is configured. BREAKING: unauthenticated clients lose access to protected listeners.
- Parse HTTP response framing and Telegram DC endpoints correctly.
- Return decoded finite telemetry gauge values, expose recorder installation failures, and reload changed host lists.

## Capabilities

### New Capabilities

- `native-contract-config-validation`: Validate native configuration, proxy access, diagnostics, telemetry, and transport contracts at their entry points.

### Modified Capabilities

- None.

## Impact

- Rust configuration, diagnostics, proxy runtime, strategy, telemetry, tunnel, and WebSocket transport crates; proxy runtime adapter tests and strategy pack documentation.
- No JNI, protobuf, storage, or schema version change. No new dependency or network service.
