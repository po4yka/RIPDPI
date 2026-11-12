## Context

The synchronous per-flow writer has separate plain and strategy error paths. The offset parser accepts fields that Split does not use. See proposal.md.

## Goals / Non-Goals

- Goal: reject ignored Split fields and preserve safe error handling.
- Goal: bound offset arithmetic and keep tail delay consistent.
- Non-goal: add packet techniques, root access, dependencies, or packet-boundary guarantees.

## Decisions

- Reject repeat greater than one and nonzero skip for Split at Kotlin, CLI, and native JSON admission. Preserve TLS-record repetitions.
- Reject unconsumed colon fields in the shared parser.
- Use checked offset arithmetic and existing Option/error paths, including dependent TLS offset adjustment where needed.
- Retain exact known progress outside receipt fields whose counters are capped. Preserve failure context in fallback errors.
- Treat a closed/cancelled in-flight first write as terminal without inventing syscall progress. Refuse route retry after runtime shutdown.
- Apply the configured delay before a nonempty tail in the special executor.

## Contracts and ownership

- split_config owns configuration admission and tests in ripdpi-config, ripdpi-proxy-config, core:data:model, core:data tests, and the primary marker field in app.
- offset_arithmetic owns ripdpi-desync offset.rs, dependent TLS offset adjustment, and existing tests.
- write_progress owns desync-runtime progress/types/tcp error receipts and proxy-runtime failure/retry/telemetry with tests. It does not edit tcp_plan/execution.rs.
- Integrator owns special-executor timing, all task/OpenSpec files, board generation, API snapshots if required, and combined validation.
- Each writer uses a dedicated worktree. Cargo.lock, schemas, dependencies, baselines, and goldens remain unchanged.

## Risks / Trade-offs

- Invalid stored configuration may now fail admission. This is intentional; supported single-split and TLS repetition remain valid.
- A failed blocking syscall can hide accepted bytes. Terminal classification is safer than replay; reported progress remains evidence, not a delivery guarantee.
- Timing checks need tolerance; peer byte equality remains the primary integrity oracle.

## Migration Plan

No storage or wire migration. Revert the affected atomic commit to roll back. Gates: targeted Rust tests and formatting, Kotlin data tests and applicable static analysis, independent read-only review, just ci-gates, architecture health, and locked Cargo metadata before main integration. Physical/device/root/external acceptance is not required for these local repairs and remains unverified.
