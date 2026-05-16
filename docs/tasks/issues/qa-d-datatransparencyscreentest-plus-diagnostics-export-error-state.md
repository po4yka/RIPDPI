---
title: QA-D: DataTransparencyScreenTest plus diagnostics export error-state assertions
type: task
status: done
area: testing
priority: high
owner: Senior Android Engineer
parent: define-diagnostics-privacy-qa-verification-gate
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-16
---

- [x] #task QA-D: DataTransparencyScreenTest plus diagnostics export error-state assertions #repo/RIPDPI #area/testing #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `qa-d-datatransparencyscreentest-plus-diagnostics-export-error-state`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

Owner: Test Automation Engineer.
Anchored to: POY-6 (acceptance) and the in-progress `Align diagnostics privacy and export copy` issue.

## Objective
1. Add a Robolectric test that asserts `DataTransparencyScreen` renders every required disclosure string from `app/src/main/res/values/strings.xml`.
2. Add error-state assertions in `DiagnosticsScreenTest` covering archive export failure and log save failure paths.

Observable behavior:
New file `app/src/test/kotlin/com/poyka/ripdpi/ui/screens/settings/DataTransparencyScreenTest.kt` asserts presence of every required `R.string.data_transparency_*` id surfaced by the screen, including:
- `data_transparency_what_we_collect_section`
- `data_transparency_what_we_do_not_collect_section` and bullets `no_browsing`, `no_personal_data`, `no_external_servers`, `no_analytics`
- `data_transparency_how_stored_section` and bullets `local_database`, `retention_period`, `disable_monitoring`, `export_explicit`
- `data_transparency_export_privacy_section` and bullets `export_redaction`, `export_control`

New assertions in `DiagnosticsScreenTest`:
- `archive export failure shows error without leaking session payload`
- `log save failure does not surface logcat content in error toast`

Success metric / test names:
- All test names above passing.
- Linked from PR description for `Align diagnostics privacy and export copy`.

Privacy implication:
Yes. Directly proves the disclosure surface and the failure-path non-leak guarantees.

Rollback note:
If any required string id is removed during copy alignment, the test must fail; the alignment PR then has to either restore the disclosure or update the test in lockstep with QA review.

Non-goals:
- Do not change copy text in this issue.

## Definition of done
- New `DataTransparencyScreenTest` and new error-state cases in `DiagnosticsScreenTest` committed and passing.
- Cross-referenced from the close-out comment of the `Align diagnostics privacy and export copy` issue.
