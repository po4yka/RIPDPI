## Context

The October 15 audit identifies log projection on Main, broad shell observation and catalog work in composition. No measured frame regression exists yet. Existing macro tests only measure startup and currently target a debuggable variant.

## Goals / Non-Goals

- Goal: measure repeated work, remove confirmed redundant work, and compare frame/input workloads on one leased emulator.
- Non-goal: visual redesign, log loss through sampling, network acceptance, new production dependencies, exact diagnostic Undo or physical-device guarantees.

## Decisions

- Project log inputs to event lists and active IDs before distinct comparison. Use the existing injected Default dispatcher for CPU work. Preserve event merge precedence and cancellation.
- Cache a catalog projection keyed by immutable profiles, query and filter. Include grouping and applied order so unrelated composition does not rescan.
- Expose a distinct permission flow derived from MainUiState and collect it in the shell. Keep MainUiState current for existing action readers, and retain its existing subscription timeout and locale behavior. A separate cold permission pipeline would leave those readers stale.
- Add a benchmark-only activity with deterministic local inputs and real production presentation. It must not enable service or scan operations or ship in release. Add a matching profileable benchmark test build type. Keep source IDs, APK hashes, repeated samples and traces outside ephemeral build directories.
- First run baseline against unchanged production code with the measurement harness, then integrate optimizations and run the same workload. Timing is descriptive on this emulator; correctness and repeated-work counts are strict checks.

## Contracts and ownership

App production Kotlin, app benchmark-only sources, baselineprofile test module and build wiring only. The portfolio task records three isolated writer lanes. The integration owner serializes build and task files. No Rust, JNI, schema, persistence, locale, golden or dependency contract changes. RDS logs and profile layouts stay unchanged.

## Risks / Trade-offs

- Equal event-list comparisons still cost work for new equal list instances; measure actual downstream mapping and preserve content equality.
- Moving work adds scheduling latency; test initial, active, cleared and unsubscribed states.
- Deterministic benchmark inputs exercise presentation, not live network or service throughput.
- No physical device is attached. Record emulator and host load limits. Do not claim release user latency guarantees.
- Disk and machine build gate are shared. Preserve existing artifacts and request exact cleanup approval when needed.

## Migration Plan

No migration. Revert the scoped app projection changes to roll back. Baseline and optimized variants use the same harness commit. Run focused tests, staticAnalysis, lint, unchanged golden verification, architecture health, locked cargo metadata and benchmark device checks before authorized fast-forward integration and push.
