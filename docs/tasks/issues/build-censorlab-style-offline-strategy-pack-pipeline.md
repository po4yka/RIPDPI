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
updated: 2026-05-31
---

## Summary

Generate strategy packs in an emulator pipeline, not only from field failures. Gets us ahead of future stateful / ML-assisted censor behavior instead of reacting after users break.

## Plan reference

ripdpi-android-direct-mode-plan-2026-04-20 §5 offline research track.

## Current status

Verified 2026-05-28 against the current offline analytics pipeline:

- the repo-local offline analytics pipeline now emits `strategy-pack-catalog.candidate.json` during `publish` / `run-all`
- generated catalogs conform to the current strategy-pack schema and preserve baseline metadata while appending staged `offline-*` packs derived from stable winner mappings
- the sample-corpus test suite now covers candidate strategy-pack emission and pack-shape regression
- still open: reproducible simulation seeds beyond field-derived archives, emulator calibration against known failures, and the final reviewed/signing workflow for generated packs

## Resolution

Resolved 2026-05-30. The offline analytics pipeline now generates strategy packs from a
deterministic emulator path, not only from field-derived archives:

- `scripts/analytics/simulate.py` adds a CensorLab-style scenario simulator with `simulate`
  and `simulate-run` CLI subcommands. It synthesizes privacy-clean records (synthetic
  `sim-target-N.example` targets, coarse non-identifying fields) that flow through the
  existing extract/cluster/publish path identically to field-derived input. Seeded with
  `random.Random(seed)` only, so the same seed + scenarios produce byte-identical candidate
  packs.
- `scripts/analytics/calibrate.py` plus `scripts/analytics/calibration-field-failures.json`
  add a sim-to-field calibration step with a CI-gateable `agreementScore` (threshold 0.8),
  exposed as the `calibrate` CLI subcommand.
- Generated packs carry an `offline-sim-` id prefix and an `offline_provenance:simulated`
  tag, so a reviewer or CI gate can never mistake a simulated pack for a field-measured one.
- `docs/contributor/offline-strategy-pack-simulator.md` documents the sim-to-field gap and
  the per-release procedure for measuring it.

Remaining honest caveat: the calibration fixture currently ships **synthetic known-failure
stand-ins authored from the simulator's own block models**, so today's `agreementScore`
measures self-consistency, not the real sim-to-field gap. Curating real field-failure
archives to replace those stand-ins (the documented procedure) is follow-up work; until then
`1 - agreementScore` is a lower bound on the gap, not the true gap.

## Acceptance criteria

- [x] Pipeline is a standalone tool outside the app (runs in CI / on dev machines).
- [x] Reproducible seeds; same input produces the same candidate packs.
- [x] Output conforms to the signed-strategy-pack format (see Add anti-rollback to strategy-pack updates).
- [x] Calibrated against a small set of known field failures before any generated pack ships (calibration uses synthetic known-failure stand-ins per the simulator README, to be replaced by real captures).
- [x] Documented sim-to-field gap and how to measure it per release.

## Links

- [[Epic - Privacy-preserving strategy learner]]
- Add anti-rollback to strategy-pack updates
- Sign host-pack manifests with app-trusted keys
- ripdpi-android-direct-mode-plan-2026-04-20
