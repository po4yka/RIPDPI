---
id: CIC-1791525871042885
title: Update pinned Go toolchains to 1.27.2
kind: chore
status: blocked
area: ci
priority: high
owner: Dependency maintenance
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-09
updated: 2026-10-09
spec_reason: dependency-only
status_detail: Current base d22389904aeefa3a621c171f006d2c4323b920c8 fails native hotspot budgets and i18n export; preflight skips native/Android consumers. Full exact-head compatibility evidence is required before merge.
---

## Goal

Use the Go 1.27.2 security patch for the existing Xray/native and outbound-peer
toolchain pins. The upstream release was published on 2026-10-08:
https://go.dev/doc/devel/release#go1.27.0.

## Ownership

Dependency maintenance owns the Go version literals in `.github/workflows/ci.yml`,
`.github/actions/build-xray/action.yml`, `scripts/tests/run-outbound-interop.py`,
`test-lab/acceptance/peers/go_toolchain.py`, and the matching assertions in
`scripts/tests/test_ci_native_dependency_graph.py`, plus this task's records and
the generated task board. No other writer is assigned to these paths for this
change.

## Acceptance criteria

- Keep all active Go 1.27 toolchain pins aligned at 1.27.2 without changing module
  language floors, module checksums, upstream revisions, NDK, or gomobile pins.
- Pass the native dependency graph, outbound suite, artifact provenance,
  acceptance peer toolchain, CI routing, and CI tool-pinning contract tests.
- Pass the task contract validator and review the complete dependency-only diff.
- Before merge, obtain successful required CI for the exact head, including
  actual Xray native production/verification, linked Kotlin consumers, outbound
  interoperability, Go fixture builds, and affected Android acceptance. Fixture
  contract tests alone do not establish native compiler/runtime compatibility.

## Verification evidence

- Prepared against `d22389904aeefa3a621c171f006d2c4323b920c8` on 2026-10-09.
- All 71 focused contract tests pass: 65 across native dependency graph, outbound
  suite dispatch, artifact provenance, CI routing, and tool pinning; six acceptance
  peer toolchain tests. The same tests passed before the pin change.
- The repository-pinned actionlint 1.7.7 passes all workflow files.
- `taskctl validate --base d22389904aeefa3a621c171f006d2c4323b920c8` passes
  for 115 tasks and 303 execution steps. The full native/Android step remains open.
- The architecture-health gate passes, but the separate native hotspot gate
  reproduces pre-existing failures: `plan.rs` is 80 LoC against 79 and
  `runners/connectivity/throughput.rs` is 112 against 46. Current base CI confirms
  these failures and skips Xray/native/Android jobs behind the preflight barrier:
  https://github.com/po4yka/RIPDPI/actions/runs/37891339210.
- The current base translation-export gate fails on 107 missing manifest keys,
  also reproduced locally:
  https://github.com/po4yka/RIPDPI/actions/runs/37891339218.
- Actual Go builds, source-pinned Xray native production/verification, linked
  Kotlin consumers, upstream outbound interoperability, and Android acceptance
  remain unverified. These are merge gates, not covered by the passing fixture
  contract tests.
