# Change: Use selected physical network authority

Task ID: `SVC-1792856195708453`

## Why

The handover monitor observes the application default, and the fingerprint and
MTU sources read activeNetwork. This source can be the VPN overlay. The monitor
can then restart a stable physical path, and the builder can subtract tunnel
overhead from an already reduced VPN MTU. Two physical stream failures show
restart events; their physical-network cause remains unproven.

## What Changes

- Use one existing selected physical observer for fingerprints and handover.
- Use the same physical evidence for tunnel network parameters.
- Keep missing or ambiguous evidence unavailable and preserve DNS authority.
- Preserve stored scope hashes and policy serialization; no storage break.

## Capabilities

### New Capabilities

- `physical-network-handover-authority`: coherent physical route evidence.

### Modified Capabilities

- None.

## Impact

- Android service observer, fingerprint, handover and tunnel configuration.
- Runtime-only model metadata and tests when required for atomic capture.
- No native protocol, persisted schema, DNS lease or live infrastructure change.
