---
title: Build CensorLab-style offline strategy-pack pipeline
type: task
status: todo
area: service
priority: medium
owner: unassigned
parent: epic-privacy-preserving-strategy-learner
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-23
---

- [ ] #task Build CensorLab-style offline strategy-pack pipeline #repo/RIPDPI #area/service #status/todo 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `build-censorlab-style-offline-strategy-pack-pipeline`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `native/rust/crates/ripdpi-strategy-registry/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Generate strategy packs in an emulator pipeline, not only from field
failures. Gets us ahead of future stateful / ML-assisted censor behavior
instead of reacting after users break.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §5 offline research track.

## Current status

This task is partially landed in `/Users/po4yka/GitRep/RIPDPI`:

- the repo-local offline analytics pipeline now emits
`strategy-pack-catalog.candidate.json` during `publish` / `run-all`
- generated catalogs conform to the current strategy-pack schema and preserve
baseline metadata while appending staged `offline-*` packs derived from
stable winner mappings
- the sample-corpus test suite now covers candidate strategy-pack emission and
pack-shape regression
- still open: reproducible simulation seeds beyond field-derived archives,
emulator calibration against known failures, and the final reviewed/signing
workflow for generated packs

## Acceptance criteria

- [ ] Pipeline is a standalone tool outside the app (runs in CI / on dev
    machines).
- [ ] Reproducible seeds; same input produces the same candidate packs.
- [x] Output conforms to the signed-strategy-pack format (see
    [[Add anti-rollback to strategy-pack updates]]).
- [ ] Calibrated against a small set of known field failures before any
    generated pack ships.
- [ ] Documented sim-to-field gap and how to measure it per release.

## Links

- [[Epic - Privacy-preserving strategy learner]]
- [[Add anti-rollback to strategy-pack updates]]
- [[Sign host-pack manifests with app-trusted keys]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
