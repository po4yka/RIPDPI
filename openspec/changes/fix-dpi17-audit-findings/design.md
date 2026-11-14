## Context

Task ENG-1794637889757963 covers the three confirmed audit findings. The current planner allocates an unbounded prelude before the original.

## Goals / Non-Goals

- Goal: bound local resources and align validation, UI text and the wire regression.
- Non-goal: change adjacent strategies, implement a QUIC endpoint or add device, root or external probes.

## Decisions

- Limit DummyPrepend to 64 per step and per UDP chain, following the finite FakeBurst precedent.
- Give direct-model planning a shared 64-attempt budget. Keep count zero as a no-op.
- Check effective fake-host length before mutation and skip lengths above 4096 bytes. Emit only packets at most 4096 bytes. Total accepted bytes are therefore at most 64 times 4096. This is a resource ceiling, not path MTU tuning.
- Reject excessive counts at user boundaries and bound internal models defensively. Preserve compact representation.
- Verify production UDP through a loopback recording peer and independent oracle observations. Add no production dependency or golden fixture.

## Contracts and ownership

- resource_budget: isolated worktree; Kotlin core/data/model validation/tests and native ripdpi-config constants, ripdpi-proxy-config validation/tests and ripdpi-desync planner/tests.
- ui_contract: isolated worktree; all ten locale sets, as the only locale writer.
- Integrator: portfolio/OpenSpec, wire_e2e, generated API snapshots, combined validation and integration.
- No schema field, Cargo.lock, dependency, baseline or golden changes.

## Risks / Trade-offs

- Counts above the limit are no longer accepted. Report a specific validation error.
- Oversized internal hosts skip the prelude and retain the original.
- Existing fail-fast behavior after an actual send error remains; UDP delivery is not guaranteed.

## Migration Plan

No stored-data migration. Run affected Rust and Kotlin regressions, locale lint, enabled wire test, independent review and just ci-gates. Rebase onto origin/main, rerun affected gates if inputs change, fast-forward main and push as authorized. A normal revert of the specific fix commit provides rollback.
