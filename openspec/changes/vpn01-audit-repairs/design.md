## Context

The audit reproduced loss of a shared route observer after delayed old cleanup.
The native loop also swallows ordinary packet-write errors and can return before
its explicit shutdown path. Reproduce both native hypotheses before implementation.

## Goals / Non-Goals

- Goal: Keep predecessor teardown ownership complete across VPN instance replacement.
- Goal: Make terminal packet-device failures retire the loop and its sessions.
- Goal: Preserve transient backpressure, cancellation, non-root behavior, and privacy.
- Non-goal: Change ambiguous legacy physical selection or add platform observers.
- Non-goal: Change wire/JNI/schema, dependencies, root features, or relay protocols.

## Decisions

- Reject attach when the bounded predecessor wait expires. A later start may retry.
  Do not expand observer, selector, and root-helper APIs with new lease types.
- Join the predecessor chain without a timeout in the background destroy job.
  This does not hold the main thread or the new service entry mutex.
- For native output failures, preserve the loop result, then cancel and run
  existing session shutdown before returning it. Stop masking fatal send errors.
- For native input failures, propagate terminal reads/readiness and EOF through
  that same cleanup path. Preserve WouldBlock behavior and existing work budgets.
- Use real local fd fault injection and live loopback sessions for native
  regressions. Kotlin tests exercise service acquisition and teardown chaining.
- Treat H3 as an intentional limit under REQ-PHYSICAL-UNAVAILABLE. Its existing
  regression suites remain in the combined JVM selection.

## Contracts and ownership

- `:core:service`: service entry, predecessor chain, and reconstruction tests.
- `ripdpi-tunnel-core`: packet input/output, loop termination, session retirement.
- JNI schema remains version 3; no persisted or wire field changes.
- No serialized dependency, locale, golden, API baseline, or architecture-health
  file changes are planned. Integration owns task/spec and documentation files.
- Independent writers and serialized lanes are recorded in the portfolio task.

## Risks / Trade-offs

- A new start can fail while old cleanup is stuck. This prevents unsafe overlap;
  test retry after completion and keep STOP handling bounded.
- EOF is unexpected for a packet device. Test real local fd closure and preserve
  transient readiness/backpressure semantics to avoid spurious disconnects.
- Host tests cannot establish Android driver, OEM, or physical handover behavior.
  Keep device evidence required and pending unless separately authorized.

## Migration Plan

No migration or dependency update is needed. Keep one commit per correction;
rollback can revert that correction after reviewing ownership consequences.
Run narrow red/green tests in each worktree, then combined affected JVM tests,
native core/adapter tests with `--locked`, `staticAnalysis`, `just ci-gates`,
`python3 scripts/ci/check_architecture_health.py`, and locked Cargo metadata.
Use independent `pr-reviewer` and async cancel-safety reviews before commits.
Integrate with a rebase on origin/main and a fast-forward merge, then push main
as explicitly authorized. Do not create releases or deploy.
