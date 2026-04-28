# ADR-011: Offline Learner Improvement Roadmap (P4.4)

**Status:** Partial — P4.4.1 implemented; P4.4.2–5 deferred.
**Date:** 2026-04-27
**Deciders:** Nikita Pochaev

---

## Context

The Strategy Evolver (`ripdpi-runtime/src/strategy_evolver.rs`) is a session-level
multi-armed bandit that selects DPI-evasion parameter combinations. It currently uses
**epsilon-greedy + UCB1** scoring with a rich fitness function (success rate, latency,
variance, detectability events, stability flips, energy cost, idle decay, cooldown).

ROADMAP-CLEANUP phase P4.4 identified five improvement areas. This ADR records the
decision taken for each sub-task.

---

## P4.4.1 — Bayesian arm scoring (Thompson sampling)

**Status: Done (this PR).**

### Decision

Added `ThompsonSampling<K>` in
`native/rust/crates/ripdpi-runtime/src/strategy_evolver/thompson_sampling.rs` as a
**standalone public type**. It is not yet wired into `StrategyEvolver`'s selection
path; UCB1 remains the production default.

### What was added

- `BetaParams { alpha, beta }` — per-arm Beta posterior with `Beta(1,1)` uniform prior.
- `sample_beta(rng, alpha, beta)` — zero-dependency sampler using **Johnk's method**
  (no new crate deps; reuses the same LCG constants as the evolver).
- `ThompsonSampling<K: Clone + Eq + Hash>` — generic bandit with `record_outcome`,
  `select_arm`, `params`, `arm_count`.
- 11 unit tests: range checks, fast-path Beta(α,1)/Beta(1,β), general mean accuracy,
  convergence test (arm-1 wins >170/200 trials after 60-success vs 60-failure training).

### Why UCB1 stays the default

1. **Backward compatibility** — UCB1 + decay + cooldown has been in production; changing
   the default mid-session would invalidate accumulated stats without a migration path.
2. **Established performance** — the current fitness function already incorporates
   detectability, variance, stability, and energy cost which go beyond a raw Bernoulli
   reward. A full Thompson integration would need a multi-dimensional reward mapping.
3. **Wiring cost** — plumbing a `ScorerKind` enum through `StrategyEvolver::new`,
   `RuntimeAdaptiveSettings`, and the Kotlin config layer is a separate, reviewable PR.

### Future wiring (next PR)

To activate Thompson in production:

1. Add `ScorerKind { Ucb1, Thompson }` to `ripdpi-config`.
2. Add `scorer_kind: ScorerKind` field to `StrategyEvolver`.
3. In `select_next_family` / `best_context_combo_for_family`, branch on `scorer_kind`:
   - `Ucb1` → current path unchanged.
   - `Thompson` → instantiate `ThompsonSampling<StrategyFamily>` / `ThompsonSampling<StrategyCombo>` keyed off `ContextBanditState`, delegate `select_arm`.
4. Persist `BetaParams` alongside `ComboStats` so posteriors survive context switches.
5. Expose the knob in `--strategy-scorer` CLI flag and `evolution_scorer_kind` settings field.

Estimated diff for wiring step: ~150 lines across config + runtime + CLI.

---

## P4.4.2 — Rarity / retry penalties in scoring formula

**Status: Deferred.**

### Current state

`combo_fitness_at` applies an energy-cost penalty (`FITNESS_ENERGY_WEIGHT = 18.0`) for
high-cost combos (fake TTL, aggressive UDP, realistic QUIC, mixed dimensions). There is
no explicit penalty for rarely-tried arms (rarity bias) or for arms that have been
retried many times without new data (retry cost).

### Why deferred

Adding rarity and retry penalties requires:

1. Defining "rarity" — absolute attempt count vs proportion of total draws vs time since
   last attempt. The decay weight (`exp(-Δt / half_life)`) partially captures staleness
   but does not penalise arms that have never been selected.
2. Avoiding double-counting with the existing UCB1 exploration term, which already
   inflates scores for under-tried arms (`1.41 * sqrt(ln(N) / n_i)`).
3. Determining the right weight magnitude without destabilising the fitness range
   (currently `-100..1000`).

### Proposed approach (future PR)

- Add `rarity_penalty(attempts: u32, total: u32) -> f64`: penalise arms with
  `attempts < RARITY_FLOOR` (e.g. 3) at a fixed offset (e.g. `-15.0`).
- Add `retry_cost(attempts: u32) -> f64`: log-damped cost for arms tried >
  `RETRY_SATURATION` (e.g. 20) times with no improvement, signalling the evolver to
  explore new regions.
- Gate both penalties behind `evolution_penalties_enabled: bool` config flag so they can
  be A/B tested independently of the scorer.

---

## P4.4.3 — Attempt-budget enforcement

**Status: Deferred.**

### Current state

`StrategyEvolver::max_combos` caps the number of combos stored (`default: 64`) and
`evict_if_needed` evicts the lowest-fitness combo on overflow. There is no per-arm or
per-session attempt budget; a single failing arm can accumulate unbounded attempts.

### Why deferred

P4.3.2 (direct-mode diagnostic orchestrator) was also scoped as "attempt-budget
enforcement with metrics" and was deferred to future work in ADR-010. The two systems
should share a common budget abstraction rather than each inventing one independently.

### Proposed approach (future PR)

- Add `max_arm_attempts: u32` to `StrategyEvolver` (default: 100). Once an arm exceeds
  this threshold, it is frozen: it can still be exploited if it is the current niche
  winner, but it is skipped during exploration.
- Expose `attempts_budget_remaining(combo)` metric for telemetry.
- Coordinate with P4.3.2 to share a `AttemptBudget` struct.

---

## P4.4.4 — Shared-priors upload constraints

**Status: Deferred.**

### Current state

There is no shared-priors upload mechanism in the codebase. ROADMAP-CLEANUP notes this
as a future feature to allow aggregated strategy data to inform new installs faster.

### Why deferred

Per the project's no-backend rule (CLAUDE.md), shared priors must be delivered as static
files (GitHub-hosted or bundled assets), not through a live API. Designing a safe,
versioned prior format with:

- Max payload size enforcement (prevent oversized bundles from blocking startup),
- Rate-limit semantics (how often can priors be refreshed?),
- Integrity verification (signed manifest),

requires a dedicated design spike. This is Phase 5+ work.

### Proposed approach (future design doc)

- Prior format: newline-delimited JSON records, each `{ combo_hash, alpha, beta }`.
- Max payload: 64 KB compressed (≈ 1000 arms at ~64 bytes each).
- Refresh rate: at most once per 24 h, checked at session start via a `Last-Modified`
  HEAD request before fetching the full payload.
- Integrity: SHA-256 manifest signed with the project's release key.

---

## P4.4.5 — Emulator / sim-to-field calibration

**Status: Deferred.**

### Current state

The evolver is trained exclusively on field data from live connections. Emulator runs
(CI, local test devices) may have different network characteristics and could pollute
production priors if naively mixed.

### Why deferred

There is no emulator-detection signal currently threaded into `LearningContext`. Adding
one requires:

1. A reliable heuristic or capability flag that identifies emulated environments
   (e.g. `ro.kernel.qemu`, `ro.hardware == "ranchu"`, or the `rooted` field extended
   to cover AVD).
2. A separate prior store for emulator-derived data, preventing cross-contamination.
3. A calibration pass that maps emulator outcomes to field-expected outcomes — a
   research task with no clear prior art for DPI evasion specifically.

### Proposed approach (future research spike)

- Extend `LearningContext` with `environment: EnvironmentKind { Field, Emulator, Unknown }`.
- Gate emulator runs to a separate `emulator_combos: HashMap` with independent stats.
- After N field sessions, compute a per-family calibration factor
  `field_success_rate / emulator_success_rate` and apply it as a prior boost for
  emulator-trained arms when deploying to field.

---

## Consequences

- `ThompsonSampling<K>` is now available in the `strategy_evolver` module for use in
  future integration work.
- P4.4.2–5 are formally scoped with proposed approaches, enabling the next engineer to
  pick up any sub-task independently.
- UCB1 + fitness function remains unchanged; no production behaviour is affected by
  this PR.
