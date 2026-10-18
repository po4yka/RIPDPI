---
id: UIX-1792259264757428
title: Control haptic feedback and deliver actual results once
kind: feature
status: doing
area: ui
priority: high
owner: Haptic integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: haptic-feedback
created: 2026-10-17
updated: 2026-10-18
status_detail: The user authorized UI acceptance without a genuine backend bundle. Use the existing repository CI fixture only as a local ignored build input, then run combined-tree checks, emulator preference acceptance, and authorized main integration and push.
---

## Goal

Let Full and Simple users follow system haptics or disable app feedback. Deliver actual operation results once without replay from retained screen state.

## Acceptance criteria

- Default and legacy settings follow Android touch feedback. Off survives restart and suppresses all app feedback without changing system settings.
- Full and Simple expose the same accessible preference. All new text exists in ten locales.
- A user connection attempt signals success only after confirmed Running. Accepted, restored Running, cancellation, telemetry and repeated status do not signal success.
- Detection emits one result per completed run, including consecutive equal verdicts. Rendering a retained result does not emit feedback. Cancellation stays quiet.
- Consumed events never replay after navigation, recomposition or Activity recreation. Disabled feedback is consumed and never replayed when enabled later.
- Meaningful RED/GREEN tests, schema/backup/default checks, lint, static analysis, independent review, and emulator acceptance pass before main integration and push.

## Ownership

- Storage worker owns proto, serializer, JSON snapshots/mappers and storage tests in RIPDPI-haptic-storage. Root serializes their integration and owns all locale sets and translation export, settings UI/state/actions, theme/startup preference, shared interaction gate, planning and integration.
- Result worker owns MainConnectionActions, MainActivityShellController, ConfigRuntimeActions, ConfigViewModel/ConfigEffect and its route/editor event consumers, Detection coordinator/ViewModel/screen, VPN permission dialog and related result tests in an isolated worktree. Root applies the small MainUiModels and MainActivityContent integration snippets to avoid shared writers. Root owns diagnostics completion tracker regression and any narrow fix.
- The result worker also owns DiagnosticsMutationRunner, DiagnosticsUiModels, the DiagnosticsViewModel sampler wiring, DiagnosticsRoute event guards and the queued-feedback regression in its isolated worktree. Root applies that patch after the observed RED and keeps locale and fixture ownership separate.
- Root owns the MainConnectionActions repeated Config-start failure regression and its narrow authority-based deduplication fix.
- Device specialist owns a new instrumented haptics test and read-only acceptance evidence in an isolated worktree, after the event interface is fixed. Root serializes all compiler runs.
- Independent reviewer is read-only. Quality baselines remain outside write scope.
- On 2026-10-18 the user authorized exactly 14 reviewed screenshot PNG updates: six InstalledReadability Russian maximum-font settings images and eight SimpleHome images, excluding simpleHomeReportCompletedMaximumFont. The golden specialist owns only these fixtures. It has exclusive write access to the dedicated haptic feature worktree during record, strict verification and the fixture commit; root performs no parallel writes. Other fixtures remain unchanged.

## Design reference

Use the existing settings row and dropdown roles from docs/design/rds/preview/components-settings-row.html. The deck has no dedicated haptics settings sheet. Reuse the same compact control in a reachable Simple interface overlay. Keep platform action constants and system opt-out behavior.
