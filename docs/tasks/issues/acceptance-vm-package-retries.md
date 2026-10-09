---
id: TST-1791554104885065
title: Retry transient acceptance VM package downloads
kind: bug
status: done
area: testing
priority: medium
owner: Linux acceptance
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-09
updated: 2026-10-10
spec_reason: tooling-only
closed_at: "2026-10-09T21:01:39Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Clean1e51343f1: full42 plus Xray repeat1/repeat2/VM-routed PASS; combined7631 executed and staticAnalysis PASS; all5 exact published code workflows PASS; independent source/JNI/runtime review CLEAR; own AVD/Lima stopped, artifacts/evidence preserved; committed review precedes terminal record."
---

## Goal

Retry transient package connection failures during acceptance VM preparation.
The initial task-owned VM failed to download Docker packages from the pinned
Ubuntu snapshot. The same package install passed with two acquisition retries.
Keep the snapshot and package set unchanged. A persistent failure must still
stop preparation.

## Acceptance criteria

- Both VM bootstrap and packet-engine preparation use `Acquire::Retries=2` for
  package index and archive downloads.
- The Ubuntu snapshot remains `20260926T000000Z`; neither path ignores missing
  packages.
- VM regression tests and shell syntax checks pass.
- Routed acceptance records baseline, fault, recovery, receipts, and cleanup.

## Evidence

The initial cloud-init failure and installed package versions remain local in
`/tmp/ripdpi-acceptance-20261009-linux/bootstrap-initial/`.
The first clean-source routed run passed all nine catalog rows for
`f52964e42ce758090a79b3a3e65233830f1ee772`; `lab.py verify` confirmed the report in
`/tmp/ripdpi-acceptance-20261009-linux/routed-initial/`.
After this change, all 22 VM regression tests, the preparation script syntax
check, and the manifest generation check passed. Final combined-source runtime
validation is tracked by the acceptance coordinator.
