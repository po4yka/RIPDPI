## Context

`AppStatus` is intentionally the coarse local runtime lifecycle axis. `MainConnectionActions` currently maps `Running` to `ConnectionState.Connected`, and Home uses that value for a locked actuator and a “Connected” VPN card. The app already observes privacy-safe `NetworkPathValidationEvidence`, but only uses it for underlay availability; its VPN path fields are not part of the Home connection claim.

## Goals / Non-Goals

- Goal: Introduce an explicit Home VPN data-plane status derived from lifecycle, active mode, and current coarse Android path evidence.
- Goal: Keep the local runtime active and stoppable while showing checking, unverified, or unavailable VPN connectivity honestly.
- Goal: Preserve the existing proxy-mode presentation.
- Non-goal: Change service startup ordering, stop a runtime when Android validation fails, add an active Internet probe, or alter relay failover policy.
- Non-goal: Change JNI, protobuf, native wire, persistence, or diagnostics archive schemas.

## Decisions

- Add an internal `VpnDataPlaneStatus` projection with `NotApplicable`, `Checking`, `Working`, `Unverified`, and `Unavailable` values. This avoids overloading `AppStatus` or `ConnectionState` with two independent meanings.
- Treat `Working` as fail-closed: it requires captured evidence with `vpnPresent=true`, `vpnInternet=true`, `vpnValidated=true`, and `vpnCaptivePortal!=true`.
- Treat explicit negative captured fields as `Unavailable`; incomplete captured evidence as `Checking`; unavailable capture as `Unverified`; stopped or proxy runtime as `NotApplicable`.
- Keep `ConnectionState.Connected` as the local runtime/control state so the stop action, metrics lifetime, and service coordination remain stable.
- Feed the typed status into the Home actuator and VPN mode card. Non-working VPN states keep the card active for deactivation but replace the positive “Connected” claim; the actuator uses a degraded route stage with state-specific copy.
- Use the existing `NetworkPathValidationSource` flow. No new polling, sockets, identifiers, or permissions are introduced.

## Contracts and ownership

- `:app` owns the new internal projection, Home UI state, actuator, card mapping, tests, and localized strings.
- `:core:diagnostics` continues to own `NetworkPathValidationEvidence`; its serialized shape is unchanged.
- `:core:service`, Rust crates, JNI, protobuf, settings, and stored diagnostics contracts are unchanged.
- Serialized shared files: nine Android locale resource sets are edited as one owned lane. No golden fixture is intentionally changed.

## Risks / Trade-offs

- Android validation can briefly lag after local startup, so the UI may show “checking” before “working” → preserve local runtime controls and transition automatically as path evidence updates.
- Some platforms may make path capture unavailable → show “unverified” instead of inferring success or failure.
- Existing code still uses `ConnectionState.Connected` for local lifecycle control → document and test `VpnDataPlaneStatus` as the authoritative positive VPN data-plane claim.
- Locale drift → add every new key to all nine shipped locale sets and run Android lint through `staticAnalysis`.

## Migration Plan

This is an additive internal UI projection with no persisted data migration or compatibility shim. Rollback consists of reverting the projection and presentation changes. Validation requires an observed TDD RED/GREEN cycle, focused app tests, the full affected app unit-test variant, `staticAnalysis`, strict OpenSpec/task validation, architecture health, and final combined-tree checks after rebasing onto `origin/main`. Physical-device path transitions and hosted CI remain distinct post-push evidence.
