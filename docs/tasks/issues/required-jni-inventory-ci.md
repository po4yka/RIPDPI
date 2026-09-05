---
id: CIC-1788597660382159
title: Validate JNI inventories in required native CI
kind: chore
status: done
area: ci
priority: high
owner: Audit integration
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-05
updated: 2026-09-05
spec_reason: tooling-only
closed_at: "2026-09-05T08:44:27Z"
closed_reason: Approved CI policy correction validated locally
evidence_summary: 49 workflow/routing tests passed, including real Git baseline policy rejection cases; all tooling tests passed. The exact new workflow shell step passed all five verified arm64 ELF inventories. Actionlint, strict harness, and file LoC gates passed; independent review reported no findings. Final PR CI remains required before merge.
---

## Goal

Allow updates to the five JNI export inventories while keeping every other baseline protected. Validate the inventories against packaged ELF exports inside required native CI. The user explicitly approved this rule correction and main integration on 2026-09-05.

## Acceptance criteria

- Only the five registered JNI inventory paths are exempt from the baseline edit guard.
- Other baseline paths, lookalike paths, and failed Git discovery remain rejected.
- Native packaging checks all five libraries before artifact upload and remains required by ci-required.
- Each inventory path routes to native CI. Run regression tests, workflow checks, and strict harness validation.

## Ownership

Audit integration owns .github/workflows/ci.yml, scripts/tests/test_ci_native_dependency_graph.py, the audit report, and this task. Review agents are read-only.
