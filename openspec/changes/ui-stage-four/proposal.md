# Change: Measure and improve UI responsiveness

Task ID: `UIX-1792226888578993`

## Why

Log snapshots are mapped again on unrelated telemetry updates on Main. Catalog filtering and grouping repeat during composition. The shell observes full telemetry-backed UI state for permissions. The audit needs observed before/after evidence.

## What Changes

- Reduce redundant projection work and keep input and scrolling responsive.
- Add deterministic measurements and a release-derived profileable benchmark lane.
- Preserve displayed content, order, filters, permissions and actions. No breaking changes.

## Capabilities

### New Capabilities

- `ui/responsiveness`: bounded UI projection and repeatable responsiveness evidence.

### Modified Capabilities

- None.

## Impact

- App UI and test-only benchmark module. No production dependency, locale, golden, Rust, service, JNI, protobuf or storage change.
