## Context

The resolver already partitions health by opaque network scope and ranks
quarantine before the composite score. Diagnostic request admission already owns
automatic target caps. Both are existing runtime consumers.

## Goals / Non-Goals

- Goal: fit DNS ranking weights and select compact repeat diagnostic targets on device.
- Non-goal: change DNS validation, packet strategies, scan verdict thresholds, or UI.
- Non-goal: run optimization against live hosts or promote generated strategy packs.

## Decisions

- Keep DE private to DNS health. Record pre-update normalized features and the
  measured transport utility. Use a chronological training/validation split.
  Bound weights around the current reliability-first model and keep oracle weight
  at least its current value. Fit a fixed population with a seeded RNG outside
  the registry mutex. Apply only a strict validation improvement over both the
  default and current models; expire state after an idle window.
- Keep bootstrap scoring unchanged: transport-only history does not justify an
  oracle-aware learned model for bootstrap addresses.
- Keep SA private to diagnostics. Cache bounded complete manual-scan evidence in
  memory by scope, profile, and path. Select automatic targets before the existing
  budget gate. Use exact outcome signatures and measured costs; protect controls,
  unknown or failed targets, and concurrency cohorts. If a feasible subset does not
  exist, preserve the prior planner. Full manual scans seed evidence but are not compacted.
- Search is synchronous, bounded CPU work on existing background scan paths.
  There is no background worker, extra socket, or new FFI entry point.
- Memory is intentionally process-local. A restart or stale evidence restores
  defaults. Persistent model storage can be designed separately if measurements justify it.

## Contracts and ownership

- One writer owns `native/rust/crates/ripdpi-dns-resolver/src/health*`,
  `core/diagnostics`, and this task's documentation.
- No Cargo lockfile, Rust public API, JNI, protobuf, database, locale, or golden changes.
- Kotlin dependency injection constructors and their in-repository callers receive the optimizer.
- Independent read-only `pr-reviewer` pass before commit.
- Post-scan artifact capture has its own recorder. This preserves the existing
  finalizer constructor budget and keeps capture privacy rules in one place.
- Gates: DNS crate tests and clippy; diagnostics unit tests; staticAnalysis;
  `just ci-gates`; `./taskctl validate`.

## Risks / Trade-offs

- Selection bias and noise: chronological holdout, minimum history, conservative
  bounds, strict improvement, and default fallback.
- Lost diagnostic evidence: exact coverage constraints, mandatory targets,
  unchanged manual scans and existing target caps.
- CPU or lock contention: fixed populations, bounded samples, fit outside lock.
- Simulated fixtures show algorithm behavior, not live-network performance gains.

## Migration Plan

No stored or wire format changes. Removing the local optimizer restores fixed
weights and prefix-based automatic target selection. No deployment is owned.
