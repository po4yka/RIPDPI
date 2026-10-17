---
id: UIX-1792259259850628
title: Smooth diagnostic progress and clarify phase changes
kind: feature
status: doing
area: ui
priority: high
owner: Diagnostic feedback integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: diagnostic-progress-feedback
created: 2026-10-17
updated: 2026-10-17
---

## Goal

Show measured scan progress smoothly and make phase changes clear without
false percentages, repeated speech, lost focus, or motion when it is disabled.

## Acceptance criteria

- Only forward measured updates in the same scan animate. Initial, reset,
  backward, unknown-to-known and disabled/reduced states show the actual value.
- Accessibility exposes the latest measured value at once, without per-frame
  live announcements. Unknown progress stays indeterminate.
- Phase names and localized states are accessible. Finite visual feedback runs
  on phase state changes only; counts and probe ticks do not replay it.
- Cancel stays available. Focus and reader position stay stable. No event-list
  animation or false completion is added.
- Normal/reduced/off tests, locale lint, theme checks, static analysis,
  independent review and real APK emulator acceptance pass.
- Actual TalkBack is active during normal/off acceptance. Retain its speech
  output and focus evidence separately from component semantics tests.
- Rebase and combined checks precede authorized ff-only main integration and
  ordinary push. Inspect exact-source hosted CI and retain its result.

## Ownership

- Root owns all locale resources, generated translation keys, shared test tags,
  planning, integration, compiler-backed checks, device test source and receipts
  in RIPDPI-diagnostic-progress-feedback.
- Progress worker owns new DiagnosticsMeasuredProgress.kt and focused tests in
  RIPDPI-diagnostic-progress-smoothing. Shared RipDpiProgressBar stays unchanged.
- Phase worker owns DiagnosticsScanProgress.kt, an adjacent phase helper, and
  focused phase tests in RIPDPI-diagnostic-phase-feedback. Root wires the new
  progress wrapper after the phase patch. The phase worker does not edit locales.
- Device specialist starts read-only. Root later grants the exclusive build and
  leased device lane. It preserves settings, service state and artifacts.
- All writers use separate worktrees. Compiler work is serialized. Review is
  read-only. Golden fixtures and quality baselines are not approved for updates.

## Design references

Use components-progress-bar.html, components-stage-progress.html,
motion-probe-pulse.html and motion-reduced-motion.html in docs/design/rds/preview.
The preview sources are read-only. Keep existing token roles and restrained
motion. A phase-only announcement is separate from animated visual values.
