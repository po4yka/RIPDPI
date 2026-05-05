---
title: Split HomeAnalysisPanels into single-responsibility panel composables
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Split HomeAnalysisPanels into single-responsibility panel composables #repo/RIPDPI #area/ui #status/backlog 🔼

## Objective

Extract summary cards, action rows, remediation sheet, verification sheet, and analysis status panels from `HomeAnalysisPanels.kt` to prevent the home diagnostics area from becoming a UI catchall.

## Context

`HomeAnalysisPanels.kt` has multiple suppressed long composables, including the diagnostics card and bottom-sheet host, and wires diagnostics actions, history, settings, mode editor, PCAP, share flow, remediation ladder, and verification state.

Source: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/home/HomeAnalysisPanels.kt:128-140`

## Acceptance criteria

- [ ] `DiagnosticsSummaryCard` composable owns the summary card rendering.
- [ ] `DiagnosticsActionRow` composable owns action button row.
- [ ] `RemediationBottomSheet` composable owns the remediation ladder sheet.
- [ ] `VerificationBottomSheet` composable owns verification state sheet.
- [ ] `AnalysisStatusPanel` composable owns PCAP/share flow and analysis status.
- [ ] `HomeAnalysisPanels` becomes a thin coordinator; suppressed long-method detekt warnings removed.
- [ ] Roborazzi home screen golden passes.

## Definition of done

Suppressed detekt violations removed; each panel composable compiles and has a preview; golden passes.
