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

**Status: Partial 2026-04-28.** The format parser, manifest verifier
(ed25519 + SHA-256), and the fail-secure `apply_priors` pipeline landed;
the Kotlin Retrofit transport, the WorkManager scheduler, and the
embedded production release key still deferred.

### Implementation (this Phase)

- `strategy_evolver/shared_priors/` module split into three sub-modules:
  - `parser.rs` — NDJSON record parser (`{ combo_hash, alpha, beta }`),
    256 KiB cap, per-record Beta-distribution validation, comment/blank
    line skipping. The legacy public API is unchanged.
  - `manifest.rs` — `SharedPriorsManifest` deserializer + ed25519
    signature verification (`ring::signature::ED25519`). The signed
    message is canonical and structurally simple:
    ```text
    signed_input = b"ripdpi-shared-priors-v1\0"  (24 bytes)
                 || priors_sha256                (32 bytes)
                 || issued_at_unix.to_le_bytes() (8 bytes)
    ```
    The domain-separation tag prevents cross-protocol signature reuse.
    Errors `InvalidJson`, `UnsupportedVersion`, `BadHashFormat`,
    `BadSignatureFormat`, `HashMismatch`, `BadSignature`,
    `NoProductionKey` cover every failure path with a typed enum.
  - `loader.rs` — `apply_priors(manifest_bytes, priors_bytes, public_key)`
    end-to-end pipeline. Fail-secure: any error returns `Err` without
    touching evolver state. Also exposes
    `canonical_combo_hash(combo) -> u64` (FNV-1a over a 14-byte fixed
    wire form using the same dimension discriminants as the in-memory
    `Hash` impl), so a future signing tool can compute identical hashes
    from any language.
- `StrategyEvolver` gained an opaque `shared_priors` store plus the
  `apply_shared_priors` / `apply_shared_priors_with_embedded_key` /
  `shared_prior_for` / `shared_priors_len` API. Successful apply replaces
  the store atomically; a failed apply leaves it untouched. The UCB1
  selection path is unchanged — priors are exposed for the Thompson
  scorer wiring (P4.4.1) and diagnostics, never overriding local field
  data (the "field data wins" rule).
- The embedded `SHARED_PRIORS_PUB_KEY` is the all-zero placeholder.
  `is_production_key_set()` reports `false` and
  `apply_priors_with_embedded_key` short-circuits with
  `ManifestError::NoProductionKey` until the project's release-signing
  key is generated and the public half is committed. This is a deliberate
  fail-closed default for production builds.
- Verified by 22 unit tests across the three sub-modules and the
  evolver integration: roundtrip-with-test-key, tampered-payload
  detection, signature-from-different-key rejection, unsupported-version
  rejection, invalid-json rejection, placeholder-key short-circuit,
  truncated-signature rejection, canonical-hash stability for the
  default combo, hash-distinguishes-Some-zero-from-None, and the
  fail-secure store semantics on the evolver.

### Still deferred

- **Embedded production public key**: still the all-zero placeholder.
  Generating the keypair, securely storing the private half, and
  committing the public half is the release-infrastructure follow-up
  that unblocks production use.
- **Kotlin Retrofit transport** (`SharedPriorsCatalogNetwork.kt`):
  HEAD-with-`Last-Modified` + GET manifest + GET priors, modeled on
  `HostPackCatalogNetwork.kt`. Plus a refresher with the 24h cooldown
  and `EncryptedSharedPreferences`-backed Last-Modified cache.
- **JNI bridge**: `applySharedPriors(manifestJson, priorsBytes)` thin
  wrapper around `StrategyEvolver::apply_shared_priors_with_embedded_key`.
- **WorkManager periodic-job hookup**, shared with the ADR-012 Phase 3
  follow-up. First cut is on-demand-only refresh.
- **Settings UI**: a `shared_priors_enabled` opt-in flag mirroring the
  existing `strategy_evolution` flag (default off).

The verification pipeline landing now means the transport layer can be
designed against a stable, fail-secure contract — the Kotlin side
becomes a thin "fetch-and-hand-off-bytes" client.

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
