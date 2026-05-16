---
title: Gate Diagnostics packet-capture surface on rootModeEnabled and add raw-packet disclosure
type: task
status: done
area: android
priority: high
owner: Senior Android Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-16
---

- [x] #task Gate Diagnostics packet-capture surface on rootModeEnabled and add raw-packet disclosure #repo/RIPDPI #area/android #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `gate-diagnostics-packet-capture-surface-on-rootmodeenabled-and-add-raw`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Objective
Bring the in-app packet-capture UI into compliance with the AppSec decision on POY-14. Today the Diagnostics-tools "Packet Capture" card is visible and operable on non-rooted devices, uses hardcoded English copy, and does not surface a raw-packet disclosure before recording starts.

## Context
AppSec POY-14 verdict: changes_requested. `DiagnosticsToolsSection.kt:124-138` renders "Packet Capture" / "Start Recording" / "Stop Recording" with hardcoded English strings and is shown unconditionally. `DiagnosticsViewModel.togglePcapRecording` flips a boolean without a `rootModeEnabled` check or a confirmation step. The Home full-analysis PCAP toggle is correctly gated (`MainHomeDiagnosticsUiState.kt:141: pcapToggleVisible = settings.rootModeEnabled`) and that gating is the model for this surface.

User story:
As a non-rooted RIPDPI user, I do not want to see or accidentally start a packet-capture flow that requires root to be useful, and as an advanced/root user I want to be told what raw data will be written before recording begins.

Affected surface:
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsToolsSection.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsRoute.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsViewModel.kt`
- `app/src/main/res/values/strings.xml` and translation siblings
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/SettingsPreferencesScreen.kt` (delete-PCAP affordance)

## Acceptance criteria
1. F-02 (High): The "Packet Capture" card in `ToolsSection` is hidden when `settings.rootModeEnabled == false`. If product wants the card visible as a "requires advanced settings" affordance, render it disabled with a string resource explaining the requirement; do not allow `togglePcapRecording` to start a recording in that state.
2. `DiagnosticsViewModel.togglePcapRecording` short-circuits to a no-op + user-visible error when `rootModeEnabled == false`.
3. F-04 (High): All `Packet Capture` card copy moves to localised string resources matching the rest of the app's translation set. The card body must mention raw-packet capture, retention (24h / 3 most-recent files), and that no automatic export occurs.
4. Pre-recording disclosure: tapping "Start Recording" on either the Diagnostics-tools surface or the Home full-analysis toggle shows a confirmation that names: raw IP packet bytes are written to a local file, retention window, that the user can stop recording at any time, and that PCAP files are not attached to normal diagnostics shares. Confirmation copy must be reviewed by Documentation/UX (POY-15) before merge.
5. F-07 (Medium): Settings exposes a user-visible "Delete recorded packet captures" action that immediately invokes `DiagnosticsArchiveFileStore.cleanupPcapFiles()` ignoring the 24h window, and shows a confirmation toast.
6. On `rootModeEnabled` transition true → false, invoke `cleanupPcapFiles()` ignoring the 24h window so advanced-mode artefacts do not persist.

## Required verification
- Compose semantics tests in `DiagnosticsScreenTest` (or new `DiagnosticsToolsSectionTest`) asserting visibility/disabled state by `rootModeEnabled`.
- Compose semantics tests asserting that tapping "Start Recording" requires a confirmation step.
- `HomeScreenTest` assertions: PCAP toggle hidden when `rootModeEnabled == false` and defaults off when toggled visible.
- Roborazzi screenshot for the Diagnostics tools card in both states.
- AppSec re-review on POY-14 once F-01..F-04 are addressed.

Privacy implication:
High. F-02 and F-04 are release-blockers for AppSec re-approval.

Rollback note:
Reverting re-exposes the unguarded card. No retained-file impact because cleanup hook still runs.

Non-goals:
- No archive exporter changes (owned by sibling remediation issue).
- No new "Share PCAP" export action (would need its own AppSec review per POY-14 §4.4).
- No DataTransparencyScreen content additions (owned by POY-15).

## Definition of done
The Diagnostics packet-capture card and `togglePcapRecording` are correctly gated by `rootModeEnabled`, all copy is localised, a pre-recording confirmation is in place, a delete-PCAP action exists in Settings, and AppSec re-approves on re-review of POY-14.
