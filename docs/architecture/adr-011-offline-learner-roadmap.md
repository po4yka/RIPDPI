# ADR-011: Offline Learner Improvement Roadmap (P4.4)

**Status:** P4.4.1 / P4.4.2 / P4.4.3 implemented; P4.4.4 partial (format
parser landed, fetch/refresh/signed-manifest still deferred); P4.4.5
remains deferred as a research spike.
**Date:** 2026-04-27 (initial), 2026-04-28 (Phase D)
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

**Status: Implemented 2026-04-28 (Phase D).** Off by default; opt-in via
`StrategyEvolver::with_learning_hardening(.., penalties_enabled = true)`.

### Implementation

- New free helpers in `strategy_evolver/types.rs`:
  - `rarity_penalty(attempts: u32) -> f64` — linear penalty per attempt below
    `RARITY_FLOOR = 3`, weighted by `RARITY_PENALTY = 5.0`.
  - `retry_cost(attempts: u32) -> f64` — log-damped term that activates above
    `RETRY_SATURATION = 20` with multiplier `RETRY_COST_FACTOR = 4.0`.
- `combo_fitness_at_with_penalties(combo, stats, now_ms, half_life_ms, penalties_enabled)`
  layers both terms when `penalties_enabled` is true. The legacy
  `combo_fitness_at` delegates with `penalties_enabled = false` so existing
  callers see no change.
- `StrategyEvolver::evict_if_needed` reads the evolver's `penalties_enabled`
  field and passes it through, so eviction rankings respect the new terms
  when the hardening is opted in.
- Verified by 3 unit tests: rarity-penalty curve, retry-cost log-damped
  growth, and an end-to-end fitness comparison showing the rare/saturated
  arm is demoted relative to a proven arm.

### Why opt-in

The penalty weights are tuned conservatively but the production fitness range
sits in `-100..1000`. Forcing the new terms on for every existing call site
would change relative ordering of stored combos in ways that could invalidate
in-flight bandit state. Opt-in via `with_learning_hardening(.., true)` lets
the runtime listener turn it on for new sessions without rewriting persisted
priors mid-flight.

---

## P4.4.3 — Attempt-budget enforcement

**Status: Implemented 2026-04-28 (Phase D).** Off by default
(`max_arm_attempts = u32::MAX`); opt-in via
`StrategyEvolver::with_learning_hardening(max_attempts, ..)`.

### Implementation

- New `pub max_arm_attempts: u32` field on `StrategyEvolver`. When a combo's
  recorded `attempts` reach the cap it is treated as *frozen*: random
  exploration (`pick_non_cooled_random_for_bucket`) skips it, but
  niche-winner pinning and the family-best exploitation paths keep using
  proven winners regardless of the cap.
- New `attempts_budget_remaining(combo) -> u32` accessor exposes the
  current headroom for telemetry consumers.
- Verified by 3 unit tests: defaults disable the cap, the random path skips
  frozen combos and falls back to the pilot when every match is frozen, and
  unfrozen siblings are still picked when only some combos are frozen.

### Coordination with ADR-010 P4.3.2

ADR-010 tracked the parallel direct-mode budget. Both systems landed in
parallel (P4.3.2 in Phase C, P4.4.3 in Phase D); they currently keep
independent counters because the two paths have different lifetimes —
direct-path attempts reset on positive transport signals, evolver attempts
reset on combo eviction. Sharing a single `AttemptBudget` struct is a
follow-up if a unified telemetry view is required.

---

## P4.4.4 — Shared-priors upload constraints

**Status: Partial 2026-04-28.** The format parser landed; fetch / refresh /
signed-manifest verification still deferred.

### Implementation (this Phase)

- New `strategy_evolver/shared_priors.rs` module with a
  newline-delimited JSON parser (`{ combo_hash, alpha, beta }`).
- Max raw payload enforced at 256 KiB (≈ 4× the ADR's 64 KiB compressed
  budget, which is generous because the parser sees uncompressed input).
  Oversized inputs short-circuit with a typed error before any line is
  parsed.
- Per-record validation: `alpha`, `beta` must both be finite and strictly
  positive (Beta-distribution constraint). Comments (`# …`) and blank
  lines are ignored. Malformed records are recorded in `Loaded::skipped`
  and dropped without aborting the bundle.
- Verified by 7 unit tests covering empty input, well-formed bundles,
  blank/comment skip, oversized rejection, non-positive alpha/beta,
  non-finite values, mixed valid/invalid, and duplicate-hash semantics.

### Still deferred

- Network fetch (no backend; GitHub-hosted asset is the intended channel).
- Refresh schedule (≤ once per 24 h via `Last-Modified` HEAD).
- Integrity: SHA-256 manifest signed with the project's release key.

The format and validator landing now means the delivery channel can be
wired up later without redesigning the interchange — the parser is the
canonical contract that any signed manifest will reference.

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
