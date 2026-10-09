# Change: Measure active packet sizes

Task ID: `DGN-1791536817237300`

## Why

The interface MTU does not measure which packet sizes a remote path can carry. Users need bounded active evidence without root or provider attribution.

## What Changes

- Add manual and full-run IPv4/IPv6 QUIC DPLPMTUD measurements, current/history cards and redacted export.
- Show ACK-confirmed UDP payload lower bounds, probe loss, the observation window and explicit method limits.
- Preserve interface MTU as a separate fact. No breaking changes.

## Capabilities

### New Capabilities

- `active-pmtu-diagnostics`: bounded active packet-size evidence.

### Modified Capabilities

- None.

## Impact

Rust diagnostics contracts/http/runner/probes/monitor, Kotlin diagnostics, generated catalog and app resources. Reuse locked Quinn. No production dependency or schema-version change.
