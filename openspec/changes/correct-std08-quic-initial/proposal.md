# Change: Correct QUIC Initial processing and active probes

Task ID: `DGN-1794211297803022`

## Why

The STD-08 audit found invalid active ClientHello messages, false positive response verdicts, lost tokens, missed ACK/coalesced CRYPTO data, false completeness, and delayed cancellation.

## What Changes

- Correct all seven confirmed audit findings with separate fix commits, including follow-ups for the IP-target variant of F1 and UDP response-size variant of F2.
- Keep Initial response evidence separate from authenticated TLS or HTTP/3 success.
- Preserve fixed fake templates and the single-datagram processing limit.
- Add an internal JNI response-validation method. No stored schema or user setting changes.

## Capabilities

### New Capabilities

- `quic-initial-processing`: bounded parsing, packet assembly and active probe integrity.

### Modified Capabilities

- None. Existing measurement scope remains unchanged.

## Impact

ripdpi-packets, ripdpi-android-platform-adapter, ripdpi-android JNI exports, native diagnostics tests and core:diagnostics. No new production dependency, locale, golden or quality-threshold changes. One JNI symbol inventory addition was explicitly approved. No breaking user-facing contract.
