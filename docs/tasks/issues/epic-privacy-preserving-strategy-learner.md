---
title: Epic - Privacy-preserving strategy learner
type: epic
status: todo
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-28
---

- [ ] #task Epic - Privacy-preserving strategy learner #repo/RIPDPI #area/epic #status/todo ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-privacy-preserving-strategy-learner`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** `epic-control-plane-hardening`
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Pick a working arm quickly with few attempts, low detectability, and low battery cost. Local Bayesian bandit per `(NetProfile, HostProfile, Arm)`; strict attempt budgets; opt-in shared priors that never leak user URLs, SSIDs, or precise location.

## Why now

The plan's explicit bottleneck is no longer "parse harder packets" — it's "find a working arm in under 6 seconds with fewer than 5 attempts." The research literature (C-Saw on measurement-with-consent; recent detection work on accumulation-based host profiling) points to exactly this shape of learner as the right answer.

## Key decisions

- **Beta posterior with four penalty terms:**

```text
score = posterior
    - 0.10 * normalized_ttfb
    - 0.08 * normalized_bytes_overhead
    - 0.15 * repeated_attempt_penalty
    - 0.20 * rarity_penalty
```

- **Rarity penalty from local frequency,** not a preset label — an arm becomes "rare" when we haven't observed similar wire images recently. Protects against accumulation-based detection.
- **Strict attempt budget** per diagnostic run:

```text
max_active_arms = 5
max_elapsed_ms  = 6000
max_probe_bytes = 65536
stop_on_first_stable_success = true
```

- **Opt-in shared priors with coarse keys only.** Upload batches keyed by `(asn, access_type, dns_class, udp443_ok, fail_phase)` — no URLs, no SSIDs, no precise location. Enforced at serialization type level, not by runtime filtering.
- **CensorLab-style offline emulator** for strategy-pack generation so we get ahead of future censor behavior instead of reacting after users break.
- **Asymmetric decay:** successful families decay more slowly than failed exact variants. A single failure must not wipe a well-earned prior.

## Scope

- **In scope:** `NetProfile` / `HostProfile` / `ArmStats` types, Beta posterior scoring with rarity + repeated-attempt penalties, attempt- budget enforcement, decay policy, opt-in shared-priors uploader with coarse-key schema, CensorLab-style offline generator.
- **Out of scope:** training remote ML models on user traffic; any path that would upload per-flow detail.

## Ship definition

- [ ] Three types defined, serde-stable, with zero user-identifying fields.
- [ ] Arm ranking exercises all four penalty terms; unit tests cover each in isolation.
- [ ] Attempt budget hard-enforced; each cap has a unit test that shows it firing first.
- [ ] Shared-priors uploader passes a static-analysis test that proves it cannot depend on URL- or SSID-carrying types.
- [ ] Offline emulator produces packs that fit the signed-pack format from [[Add anti-rollback to strategy-pack updates]].

## Current status

Verified 2026-05-28 against the current offline analytics pipeline:

- the existing offline analytics pipeline no longer stops at device-fingerprint clusters and winner mappings; it now also emits a review-gated `strategy-pack-catalog.candidate.json`
- generated packs reuse the live strategy-pack schema and baseline catalog metadata, and append staged `offline-*` packs derived from stable winner mappings
- the slice is still intentionally offline-only: generated packs are not consumed by runtime ranking automatically and still require analyst review plus the normal signing/promotion flow
- the runtime learner pieces remain open: Bayesian scoring, rarity/retry penalties, attempt-budget enforcement, and shared-priors serialization rules

## Child tasks

**Types**
- [[Define NetProfile HostProfile and ArmStats]]

**Ranking**
- [[Implement Bayesian posterior arm scoring]]
- [[Add rarity and repeated-attempt penalties to arm ranking]]
- [[Decay successful families slower than failed variants]]

**Budget enforcement**
- [[Enforce diagnostic attempt budget]]

**Shared priors and offline generation**
- [[Opt-in shared priors with coarse keys only]]
- [[Build CensorLab-style offline strategy-pack pipeline]]

Child tasks roll up via the TaskNotes relationships view on this note.

## Dependencies

- Feeds: [[Epic - Direct-mode diagnostic state machine]] (Phase 3 arm ranking consumes this learner).
- Depends on (for offline pipeline): [[Add anti-rollback to strategy-pack updates]] and [[Sign host-pack manifests with app-trusted keys]] under [[Epic - Control-plane hardening]].

## Risks / open questions

- Coarse-key entropy: how many buckets before `(asn, access_type, dns_class, udp443_ok, fail_phase)` becomes identifying? Audit on real-world data before enabling the upload by default.
- Emulator sim-to-field gap: calibrate on known field failures before any generated pack ships.

## Links

- [[ripdpi-android]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]] §5
- Child issues: 7
