# Change: Correct QUIC Initial connectivity evidence and transport

Task ID: `DGN-1794734098746391`

## Why

The connectivity probe can report a usable path after a TLS rejection or a Version Negotiation reply. Evidence from a proxy or a skipped target can also affect the stored direct-path capabilities. The probe can truncate permitted replies, repeat a packet number, or continue after cancellation.

## What Changes

- Validate the frames in correlated Initial packets and record the kind of reply.
- Keep limited response evidence separate from direct capabilities and blocking recommendations.
- Preserve whole UDP datagrams, bound the exchange, and check cancellation before a repeat.
- Reject invalid SNI and destination inputs before the exchange.
- Update the QUIC observation vocabulary with explicit response and failure states.

## Capabilities

### New Capabilities

- `quic-connectivity-probe` records the requirements for the scheduled connectivity probe.

### Modified Capabilities

None.

## Impact

Packet helpers, diagnostics transport, scheduled Rust runner and observations, and Kotlin diagnostics projections. The probe does not complete TLS or HTTP/3.
