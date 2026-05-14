---
title: QA-A: PCAP-exclusion assertions in DiagnosticsArchiveExporterTest for every archive reason
type: task
status: doing
area: testing
priority: high
owner: Senior Android Engineer
parent: define-diagnostics-privacy-qa-verification-gate
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task QA-A: PCAP-exclusion assertions in DiagnosticsArchiveExporterTest for every archive reason #repo/RIPDPI #area/testing #status/doing ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `qa-a-pcap-exclusion-assertions-in-diagnosticsarchiveexportertest-for-every`
- **Verify:** `./gradlew :core:diagnostics:testDebugUnitTest --tests DiagnosticsArchiveExporterTest`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/src/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

Owner: Test Automation Engineer.
Anchored to: POY-14 AppSec changes_requested verdict and POY-13 CTO PCAP boundary.

## Objective
Prove that no `*.pcap` entry leaks into normal diagnostics archives. Add per-reason assertions to `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsArchiveExporterTest.kt` and to renderer-level coverage if the cleanest gate sits at the renderer.

Observable behavior:
For each of `DiagnosticsArchiveReason.SHARE_ARCHIVE`, `SAVE_ARCHIVE`, `SHARE_DEBUG_BUNDLE`, `SHARE_HOME_ANALYSIS`:
- Seed `FakeDiagnosticsHistoryStores` so that `DiagnosticsArchiveFileStore.getRecentPcapFiles()` would normally return at least one fixture file.
- With `rootModeEnabled=false` (the non-root baseline), assert the produced zip contains zero entries whose name ends in `.pcap`.
- Assert no PCAP byte content is written into any CSV/manifest/provenance/developer-analytics entry.
- Assert `manifest.includedFiles` does not list a PCAP entry when the source flag is off.
- One additional positive case: with the explicit advanced opt-in flow simulated (rootModeEnabled=true AND user explicit confirmation), the zip MAY include a PCAP entry; this asserts the gate is intentional, not accidental absence.

Success metric / test names:
- `createArchive excludes pcap from share archive when root mode disabled`
- `createArchive excludes pcap from save archive when root mode disabled`
- `createArchive excludes pcap from support bundle when root mode disabled`
- `createArchive excludes pcap from home composite when root mode disabled`
- `createArchive includes pcap only with explicit advanced opt-in`

Privacy implication:
Yes. This is the verification artifact for POY-14 PCAP exclusion. Without this test the implementation issue (Remove PCAP from normal diagnostics archives and harden developer-analytics.json) cannot close.

Rollback note:
If the implementation cannot honor exclusion at the source-loader layer, document the alternative gate point and wire the assertion at that layer instead — do not mark POY-16-A green until the assertion exists.

Non-goals:
- Do not implement the production code change in this issue. Stays scoped to test additions.

## Definition of done
- Five new test methods committed and passing under `./gradlew :core:diagnostics:testDebugUnitTest --tests DiagnosticsArchiveExporterTest`.
- Linked from the implementation PR (POY-14 follow-up issue).
