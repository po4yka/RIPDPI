# Change: Add constant-rate traffic shaping with VoIP camouflage profile

Task ID: `RST-1786264762917193`

## Why

Add an outbound traffic-shaping layer that emits packets at a fixed rate and size (e.g. 200-byte UDP every 20 ms — Opus-over-RTP shape) regardless of payload arrival rate. This defeats both inter-packet-arrival-time (IPAT) and packet-size-distribution fingerprinting that DPI uses to distinguish "bulk file transfer masquerading as VoIP" from real VoIP

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `add-constant-rate-traffic-shaping-voip-camouflage`: Add constant-rate traffic shaping with VoIP camouflage profile

### Modified Capabilities

- None.

## Impact

- Portfolio area: `rust-native`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
