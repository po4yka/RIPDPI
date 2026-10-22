# Change: Repair VPN rollout audit defects

Task ID: `VPN-1792657107995294`

## Why

The rollout audit found subscription credential disclosure, missing protected relay DNS, lost runtime ownership after failure, disabled refresh for new subscriptions, and an incompatible nested selector in server bundles.

## What Changes

- Remove subscription URLs from public SHARE exports.
- Resolve native relay hostnames through the existing Android underlay callback.
- Stop surviving runtimes after a component exits; retain ownership until cleanup completes.
- Enable and schedule refresh for new long-lived subscriptions; preserve existing choices and one-time tokens.
- Handle supported server bundle candidates without silent loss and reject unsupported-only configurations.
- Add regression tests and record compatibility against the current deployment repository.

## Capabilities

### New Capabilities

- `vpn-rollout-audit-repairs`: privacy, lifecycle, refresh, and recipient compatibility guarantees.

### Modified Capabilities

- None. Existing wire schemas and native method signatures remain unchanged.

## Impact

Kotlin data, service and import modules; native relay JNI adapter; regression tests and server integration documentation. No production dependency, deployment or protocol implementation is added. Research protocol support remains an explicit compatibility boundary.
