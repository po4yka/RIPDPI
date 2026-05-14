---
title: Add strategy-pack compatibility hints for AmneziaWG servers
type: task
status: done
area: outbound
priority: low
owner: unassigned
parent: epic-amneziawg-outbound-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [x] #task Add strategy-pack compatibility hints for AmneziaWG servers #repo/RIPDPI #area/outbound #status/done 🔽

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-strategy-pack-compatibility-hints-for-amneziawg-servers`
- **Verify:** `just test-module core:data:catalog`
- **Scope (only modify these + this file + the ledger):** `core/data/catalog/**`, `native/rust/crates/ripdpi-strategy-registry/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Teach the strategy-pack metadata schema that AmneziaWG profiles are
"server-coordinated fixed config": the obfuscation params must match
the server exactly, and the strategy learner must not vary them.

## Context

RIPDPI's strategy learner rotates TLS arms, QUIC variants, direct-mode
verdicts, etc. AmneziaWG's obfuscation params are part of the server's
config; varying them client-side would break every handshake. The
learner should treat AWG profiles as opaque and not emit candidate
arms that touch `Jc/Jmin/Jmax/S1–S4/H1–H4/I1–I5`.

## Acceptance criteria

- [ ] Strategy-pack schema (`StrategyPackCatalog`) gains a
    `fixed_config_protocols` field listing protocol types whose
    params must not be varied.
- [ ] `amneziawg` is included in that list in the default pack.
- [ ] Strategy learner / candidate generator honors the field: no
    generated arm mutates an AWG profile's obfuscation params.
- [ ] Runtime selector respects the hint: it still picks between
    AWG profiles within a group, but never rewrites an individual
    AWG profile's params.
- [ ] Documented in `docs/strategy-packs.md` so offline pack authors
    know the constraint.
- [ ] Unit test: an attempt to vary an AWG profile's `Jc` in a
    generated candidate is rejected in the pack-validation pass.

## Links

- [[Epic - AmneziaWG outbound support]]

## Work log

- 2026-05-14 — Added the `fixedConfigProtocols` field to
  `StrategyPackCatalog` (`core/data/catalog`), defaulting to a list that
  includes `amneziawg` via `DefaultStrategyPackFixedConfigProtocols`. Because
  the catalog JSON decoder uses `ignoreUnknownKeys`, older bundled catalogs
  decode with this default, so the constraint ships without a re-issue. Added
  `StrategyPackCandidateArm` / `StrategyPackCandidateArmValidation` types plus
  `StrategyPackCatalog.isFixedConfigProtocol()` and `validateCandidateArm()`:
  a candidate arm that mutates any param of a fixed-config protocol is
  rejected in the pack-validation pass, while a selection-only arm (empty
  `mutatedParams`) is allowed so the runtime selector can still pick between
  AWG profiles. Documented in `docs/strategy-packs.md` for offline pack
  authors.
- Scope note: the issue's goal-contract scope also lists
  `native/rust/crates/ripdpi-strategy-registry/**`, but the executor's task
  brief explicitly forbids touching `native/**`. The Rust-side learner /
  candidate-generator enforcement is therefore left for a native follow-up;
  the Kotlin catalog schema, validation, default-pack inclusion, and docs are
  complete and the constraint is expressible and validated on the Kotlin side.
- New test: `core/data/src/test/kotlin/com/poyka/ripdpi/data/StrategyPackFixedConfigHintTest.kt`
  (default-pack inclusion, case-insensitive matching, JSON round-trip + back-
  compat, AWG-param mutation rejection across all `Jc/Jmin/Jmax/S/H/I`
  families, selection-only arm allowed, non-fixed-config protocol unaffected).
  TDD: tests written first, confirmed RED, then GREEN.
- Verify: `./gradlew :core:data:testDebugUnitTest` — BUILD SUCCESSFUL
  (exit 0). `:core:data:catalog:detekt` and `:core:data:detekt` —
  BUILD SUCCESSFUL (exit 0); `ktlint` clean on all changed files.
- 2026-05-14 — Native follow-up implemented. Added
  `native/rust/crates/ripdpi-strategy-registry/src/fixed_config.rs`
  (`CandidateArm`, `FixedConfigProtocols` with a `Default` of
  `["amneziawg"]`, `CandidateArmViolation` via `thiserror`,
  `validate_candidate_arm` / `filter_candidate_arms`) mirroring the Kotlin
  `StrategyPackCatalog.isFixedConfigProtocol` / `validateCandidateArm`
  semantics (case/trim-insensitive match, selection-only arm allowed).
  Re-exported from `src/lib.rs` and exposed thin
  `StrategyRegistry::validate_candidate_arm` /
  `filter_candidate_arms` delegates. TDD: new integration test
  `native/rust/crates/ripdpi-strategy-registry/tests/fixed_config.rs`
  written first, confirmed RED (fails to compile), then GREEN.
  Verify: `cargo nextest run --manifest-path native/rust/Cargo.toml -p
  ripdpi-strategy-registry` — 31 tests passed (exit 0);
  `cargo clippy ... -p ripdpi-strategy-registry --all-targets -- -D
  warnings` — clean (exit 0); `cargo fmt -- --check` — clean (exit 0).
