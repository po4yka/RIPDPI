---
title: QA-B: developer-analytics.json allow-list assertions in DiagnosticsArchiveExporterTest
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

- [ ] #task QA-B: developer-analytics.json allow-list assertions in DiagnosticsArchiveExporterTest #repo/RIPDPI #area/testing #status/doing ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `qa-b-developer-analytics-json-allow-list-assertions-in`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

Owner: Test Automation Engineer.
Anchored to: POY-14 AppSec changes_requested verdict.

## Objective
Replace `NoopDeveloperAnalyticsSource` in `DiagnosticsArchiveExporterTest` with a capturing fake that produces realistic content, then assert the on-archive `developer-analytics.json` payload matches the disclosure surface on `DataTransparencyScreen` for every archive reason.

Observable behavior:
- Disallowed in normal exports: `lastPanicBacktrace`, `nativeLibDigests`, `breadcrumbs`, `pcapManifest`, raw config diff fields including `rootModeEnabled` and `enableCmdSettings`.
- Allowed in normal exports: only the fields enumerated by `DataTransparencyScreen` strings.
- The allow-list table is identical for `SHARE_ARCHIVE`, `SAVE_ARCHIVE`, `SHARE_DEBUG_BUNDLE`, `SHARE_HOME_ANALYSIS` unless POY-14 verdict explicitly carved out a different scope for the support bundle (currently it did not).
- Negative test: any future addition to `DefaultDeveloperAnalyticsSource` fails the test until either the disclosure copy is updated or the field is excluded.

Success metric / test names:
- `developer analytics excludes undisclosed fields from share archive`
- `developer analytics excludes undisclosed fields from save archive`
- `developer analytics excludes undisclosed fields from support bundle`
- `developer analytics excludes undisclosed fields from home composite`
- `developer analytics allowed fields match data transparency disclosure`

Privacy implication:
Yes. This is the regression guard for the POY-14 hardening work.

Rollback note:
If the production code emits new fields, the test must fail loudly. No silent baseline expansion.

Non-goals:
- Do not change `DefaultDeveloperAnalyticsSource` itself in this issue.

## Definition of done
- Five test methods committed and passing.
- Failing-test demo recorded in PR description showing what happens when an undisclosed field is reintroduced.
