# Change: Introduce a VPN-session Hilt scope to reset per-session service state

Task ID: `AND-1786264762917810`

## Why

The 2026-06-10 Kotlin audit found Hilt has grown to 134 SingletonComponent modules (up from 71+) with no custom VPN-session scope. Several service-layer singletons logically belong to a VPN-session lifetime — ServiceStateStore, RootHelperManager, VpnAppExclusionPolicy, VpnDhtMitigationPolicy, NetworkFingerprintProvider — yet are @Singleton, so state accumulated in one session persists into the next unless explicitly cleared (e.g., a stale ServiceStateStore emitting previous-session telemetry to…

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `introduce-vpn-session-hilt-scope`: Introduce a VPN-session Hilt scope to reset per-session service state

### Modified Capabilities

- None.

## Impact

- Portfolio area: `android`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
