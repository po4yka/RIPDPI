## Context

The service shell receives a checked RuntimeAppliedIntent. Normal start carries it in RuntimeCommandStartAuthority. Explicit transport activation currently drops it, so the cold startup guard fails.

## Goals / Non-Goals

- Goal: Preserve captured authority and cancellation for explicit transport activation.
- Non-goal: Change automatic failover, native transport behavior, or the runtime startup guard.

## Decisions

- Require a captured intent at explicit transport command admission.
- Pass the immutable intent through the queue. Use the existing RuntimeCommandStartAuthority context and runUnderIntentGuard watcher at activation.
- Keep the orchestrator guard strict. Do not fetch fresh authority after suspension.

## Contracts and ownership

- Android lifecycle owns ServiceShellDelegate and focused tests in core/service.
- No Rust, serialized shared file, dependency, wire, or persistence change.
- Root owns integration and physical test scheduling; native and migration workers own their separate fixes.

## Risks / Trade-offs

- Superseded activation could publish stale state: test cancellation during suspension and rejection before queue execution.
- Automatic restart could regress: run the existing shell failover tests with the new tests.

## Migration Plan

No data migration. Roll back the source commit if necessary. Run the focused regression before and after the fix, then core service unit tests and static analysis on the combined tree. Repeat owned physical AWG acceptance before closure.
