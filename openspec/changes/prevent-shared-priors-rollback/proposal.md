# Change: Prevent signed shared-priors rollback

Task ID: `RST-1790427044011189`

## Why

A correctly signed older bundle can replace newer shared priors. The registry loses its release history after process restart, so an in-memory check cannot stop a replayed older release.

## What Changes

- Reject older signed bundles and conflicting bundles with the same issuance timestamp.
- Preserve the last accepted release marker across app restarts before publishing new priors.
- Record refresh success only after the native apply succeeds.
- BREAKING: Native global-apply calls must provide a writable private path for the release marker.

## Capabilities

### New Capabilities

- `shared-priors-rollback`: Durable release ordering for signed shared-priors bundles.

### Modified Capabilities

- None.

## Impact

- `ripdpi-shared-priors`, Android JNI and platform adapters, `core/engine`, and `core/service`.
- New app-private marker format; no production dependency or backend service.
