---
title: Gate L7 adversarial emulator in the Phase-16 release matrix
type: task
status: review
area: testing
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-25
updated: 2026-05-25
---

- [ ] #task Gate L7 adversarial emulator in the Phase-16 release matrix #repo/RIPDPI #area/testing #status/review 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `gate-l7-adversarial-emulator-in-phase16-release-matrix`
- **Verify:** `TODO(verify): ./gradlew test`
- **Scope (only modify these + this file + the ledger):** TODO(scope): <module path(s) this task may modify>
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Promote the existing L7 adversarial emulator from standalone dry-run/live smoke coverage into a Phase-16 release-gate lane that reports adversary-pattern pass, fail, or partial verdicts next to packet-smoke evidence.

## Motivation

The emulator now reproduces deterministic L7-path failure modes, but release confidence still depends on operators reading separate artifacts. Phase-16 should make adversarial evidence first-class so a green release cannot hide that only synthetic packet shapes were checked.

## Scope

- In scope: matrix row contract, artifact naming, verdict summary ingestion, and release-gate documentation for the L7 adversarial harness.
- Out of scope: adding new adversary patterns beyond the v1 emulator surface.

## Acceptance criteria

- [x] Phase-16 can select an L7 adversarial lane without requiring real-provider hardware.
- [x] The lane emits a machine-readable verdict report and links it from the Phase-16 artifact summary.
- [x] Release documentation explains how L7 emulator evidence differs from real-provider SIM evidence.
- [x] Tests prove a failed adversary-pattern cell fails the release lane.

## Work log

- Changed files: `.github/workflows/phase16-matrix.yml`, `contract-fixtures/phase16_lab_matrix.json`, `scripts/ci/phase16_matrix.py`, `scripts/ci/run-phase16-matrix-entry.sh`, `scripts/ci/phase16_pcap_summary.py`, `scripts/tests/test_phase16_matrix.py`, `docs/testing.md`, and this task note.
- Test run: `python3 scripts/ci/phase16_matrix.py validate`; `python3 -m unittest scripts.tests.test_phase16_matrix`; `python3 scripts/ci/phase16_matrix.py emit-github-matrix --filter l7_adversarial_emulator_v1_1`; `bash -n scripts/ci/run-phase16-matrix-entry.sh`.
- Remaining risk: the live GitHub-hosted L7 dry-run lane still needs a workflow dispatch to verify runner image/package behavior; local tests validate matrix selection, manifest/summary generation, and fail-closed blocked-cell behavior with an injected verdict report.

## Links

- [Design spike: L7 adversarial emulator](../../architecture/spike-l7-adversarial-emulator.md)
