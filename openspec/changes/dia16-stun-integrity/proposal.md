# Change: Correct STUN observations and path contracts

Task ID: `DGN-1794747967332880`

## Why

The DIA-16 audit found seven defects. Network consent does not cover WebRTC STUN. A default socket is labelled VPN. Invalid messages can become observations. Missing measurements can display OK. Address differences and cancellation are handled incorrectly.

## What Changes

- Apply the network master switch to WebRTC STUN.
- Label the app-default path accurately and preserve explicit unsupported capabilities.
- Validate the full STUN message and response peer before use.
- Treat path differences as observations, not proof of a leak.
- Report incomplete measurements without an OK status.
- Close blocking probe resources on cancellation within a bounded budget.

## Capabilities

### New Capabilities

- `diagnostics/stun-observation-integrity`: Define consent, ownership, wire, outcome, and cancellation rules for STUN observations.

### Modified Capabilities

- None. Existing unauthenticated UDP STUN and optional SOCKS5 entry points remain available.

## Impact

- Kotlin detection and app presentation modules, their tests, and detection export fields.
- Additive path values. Reuse the shared category execution status from the current main branch. No JNI, protobuf, database, Rust, dependency, credential, release, or deployment changes.
- No public or physical-device probes. Use local fixtures and host or emulator tests.
