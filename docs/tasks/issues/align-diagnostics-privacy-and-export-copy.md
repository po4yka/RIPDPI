---
title: Align diagnostics privacy and export copy
type: task
status: doing
area: android
priority: medium
owner: Documentation Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-16
---

- [ ] #task Align diagnostics privacy and export copy #repo/RIPDPI #area/android #status/doing 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `align-diagnostics-privacy-and-export-copy`
- **Verify:** `just lint`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Objective
Align RIPDPI user-facing privacy and diagnostics copy with the approved export/PCAP boundary after CTO and AppSec decisions.

## Context
POY-6 produced the acceptance gate and found copy gaps: README privacy promises mention no full packet captures while app/archive paths can expose PCAP; share/save archive strings say raw report data stays intact but do not plainly enumerate exclusions such as payloads, credentials, TLS secrets, or PCAP conditions; support bundle copy says app-visible logcat and recent debug information without enough user-facing detail.

User story:
As a non-technical RIPDPI user, I want export and privacy copy to say what is collected, what is not collected, and when advanced packet capture is included, so that I can decide whether to share diagnostics.

Affected surface:
README.md, README-ru.md if maintained in parallel, data transparency screen, Diagnostics share/save archive cards, support bundle copy, Home PCAP toggle/helper, Diagnostics packet-capture card.

## Acceptance criteria
1. User story: As a non-technical RIPDPI user, I want export and privacy copy to say what is collected, what is not collected, and when advanced packet capture is included, so that I can decide whether to share diagnostics.
2. Observable behavior: A user can read the affected UI/docs and see: what the export contains, what it excludes, whether PCAP is included, whether logcat is app-scoped, retention/deletion expectations, and that sharing happens only through explicit user action.
3. Success metric or test name: Updated copy is covered or explicitly verified by `RipDpiScreenCatalogScreenshotTest.diagnosticsShareScreen`, `AdvancedSettingsScreenCharacterizationTest.diagnostics section renders`, `DiagnosticsScreenTest` archive/share assertions, or equivalent screenshot/UI test names chosen by QA.
4. Privacy implication: Yes. This changes privacy disclosure copy and must not begin until AppSec approval in POY-14 and CTO boundary in POY-13 are available.
5. Rollback note: Copy-only changes are reversible by reverting strings/docs. If the approved decision requires migration or retained-file cleanup copy, document the user-visible fallback state.
6. Explicit non-goals: This issue does not implement archive behavior. This issue does not alter diagnostic collection scope. This issue does not approve PCAP recording.

Privacy implication:
High. User-facing privacy claims must match implementation and AppSec-approved wording.

## Required verification
Diff of actual copy changes; AppSec written approval; QA confirmation of screenshot/UI coverage or explicitly unchanged baselines.

Rollback note:
Revert copy/docs if AppSec or QA rejects the framing; no data migration unless POY-13/POY-14 requires retained-file cleanup language.

Non-goals:
- No Kotlin/Rust behavior changes.
- No new diagnostics targets or telemetry fields.
- No release approval.

## Definition of done
Approved copy is committed to repo files, screenshots/tests are updated or confirmed unchanged, and AppSec/QA review comments are present.

## Work log

- 2026-05-16: Dropped orphaned blocker references 'UNRESOLVED-POY-13' and 'UNRESOLVED-POY-14' (files do not exist).
