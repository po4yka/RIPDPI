---
id: CIC-1791525871042885
title: Update pinned Go toolchains to 1.27.2
kind: chore
status: done
area: ci
priority: high
owner: Dependency maintenance
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-09
updated: 2026-10-10
spec_reason: dependency-only
status_detail: CI run 38032180477 passes all 47 jobs on 0d8ae556724967647d95ecc8101e5e4f1137a847; independent review has no actionable findings.
closed_at: "2026-10-10T07:29:34Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: CI run 38032180477 attempt 1 passes all 47 jobs and ci-required on 0d8ae556724967647d95ecc8101e5e4f1137a847. Xray producer, linked Kotlin, upstream peers, locked Go fixture builds, four ABIs, and Android API 27/33/35/36/37 including API 35 Xray TUN acceptance pass. 71 local contracts and full workspace Clippy pass; independent review has no actionable findings.
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

- Hosted CI run `38032180477`, attempt 1, passes for head
  `0d8ae556724967647d95ecc8101e5e4f1137a847`: all 47 executed jobs and
  `ci-required` succeed. This includes Xray native production and verification,
  linked Kotlin tests, SSH/Mieru/AnyTLS interoperability, locked Go peer tests and
  builds, all four native ABIs, and Android API 27/33/35/36/37 instrumentation.
  API 35 includes the real Xray provider TUN acceptance checks.
  https://github.com/po4yka/RIPDPI/actions/runs/38032180477.
- CodeQL, secret scan, harness checks, and the routed acceptance contract workflow
  also pass for that head. The standalone acceptance peer and Android lanes are
  skipped by their workflow route; the actual Xray TUN checks run in the main CI
  instrumentation job as recorded above.
- Independent review found no actionable issues in the Go pin changes. Local
  full-workspace Clippy and all commit hooks pass. Go resolves the actual
  `go1.27.2` toolchain. The previous unresolved evidence below is historical and
  is now superseded by these observed results.

- On 2026-10-10, merged current main `e9471d33ac458a31d75b37212968cfce3de5a720`
  into the PR branch. This preserves published history and includes the existing
  native hotspot fixes. The hotspot gate reports zero files over budget.
- On the combined tree, 65 dependency, outbound, artifact, routing, and tool pin
  contract tests pass. Architecture health, file LoC limits, native architecture
  contracts, and locked Cargo metadata pass. Hosted compatibility checks remain
  required before merge.

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
