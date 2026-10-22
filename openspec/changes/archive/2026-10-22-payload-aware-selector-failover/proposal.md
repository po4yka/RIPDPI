# Change: Verify payloads before automatic selector failover

Task ID: `TRN-1792659369802828`

## Why

A successful small HTTP response does not prove that a relay can transfer useful
payloads. The selector currently treats an empty success response as a healthy
path and has no failure hysteresis or retry backoff.

## What Changes

- An explicit versioned bundle extension supplies two independent HTTPS payload controls and bounded failover policy.
- Automatic selection requires a full response with the configured byte count and SHA-256 through the candidate relay.
- Consecutive failure and recovery thresholds, cooldown and bounded backoff avoid repeated switching; observations are scoped to the active network and policy.
- Existing ordinary URL-test imports retain their existing behavior; the new capability is opt-in and has no direct-egress fallback.

## Capabilities

### New Capabilities

- `payload-aware-selector-failover`: payload verification and stable automatic switching for imported selector groups.

### Modified Capabilities

- None.

## Impact

- Subscription importer and runtime-state models, service candidate HTTP measurements, app selector controller and shared bundle schema mirror.
- Coordinated deployment emitter change; no new production dependency, JNI API, server backend or live deployment.
- New opt-in extension is rejected when malformed. Ordinary official sing-box artifacts remain standard and provide RTT checks only.
