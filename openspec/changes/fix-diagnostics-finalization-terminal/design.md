## Context

The report persister marks an in-path session completed before finalization stores resolver, network, and post-scan evidence. The raw-path path already defers terminal status to its settlement barrier.

## Goals / Non-Goals

- Goal: Keep the native report and publish accurate terminal status after in-path finalization.
- Non-goal: Change probe execution, report shape, or policy selection.

## Decisions

- Stage the in-path report and probe results with `running` status. Publish `completed` only after the remaining writes succeed. Use the existing session record; no new state table is needed.
- If a retry of finalization fails after report staging, publish `failed` while retaining the staged report. The existing partial-report path remains for failures before staging.
- Leave raw-path status publication in `RawPathSettlementBarrier`.

## Contracts and ownership

- Only `:core:diagnostics` Kotlin finalization, execution coordination, and tests change. No Rust crate, JNI, wire, storage schema, or migration changes.
- This writer owns those files. Other writers own export, `dpi`, `dpich`, and `rkn`. No serialized shared file is changed.

## Risks / Trade-offs

- A process exit between report staging and final status leaves a running row. The existing startup recovery marks interrupted rows failed.
- A repeated finalization attempt can repeat a side effect after a transient failure. The regression test uses a persistent post-scan fault and checks that it cannot publish success.

## Migration Plan

No data migration. Existing terminal rows remain unchanged. Revert the local code commit to roll back. Validate the focused finalization test, then the full `:core:diagnostics:testDebugUnitTest` gate with `-Pripdpi.skipNativeBuild=true`; native and device evidence remain separate.
