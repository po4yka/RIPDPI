---
id: UIX-1792040423920590
title: Correct audited Arabic localization
kind: bug
status: review
area: ui
priority: high
owner: Localization maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-audited-arabic-localization
created: 2026-10-15
updated: 2026-10-15
status_detail: Published source 632f25dc; all seven hosted workflows and combined local gates passed.
---

## Goal

Correct the 86 active Arabic resource entries and eight shared Home presentation groups confirmed at source 4e132e8c0e538c96fd46cf5a68abd058a500736e.

## Acceptance criteria

All audited copy preserves the English meaning and safety instructions. Generated Home captions, summaries and recommendations follow the selected app language, including EN to Arabic to EN with retained data. Preserve raw records, exports, identifiers and custom text. All ten locales have key and placeholder parity. Behavior regressions, full app tests, both app lint variants, service lint, static analysis and screenshot comparison pass. Rebase, integrate into main, push and observe successful hosted CI on the published source.

## Ownership

- Resource writer: all locale XML sets, all 86 audited keys, new Home keys in all ten locales, export manifest, glossary/provenance, resource regressions and the shared Arabic test layout assertion helper. Worktree RIPDPI-ar-resources-20261015. No other writer edits resource sets.
- Home writer: MainHomeDiagnosticsUiState and new app-only Home presentation resolvers, plus Home behavior regression tests. Worktree RIPDPI-ar-home-20261015. No resource, core contract, storage or export edits. Send a key manifest to the resource writer before implementation.
- Home writer also owns the runtime-confirmed RTL fix: SettingsRow value direction, LocalBypassConfigScreen and VpnConfigScreen endpoint call sites, and a separate production-screen RTL regression test in RIPDPI-ar-home-20261015. No blanket monospace direction change.
- Integration owner: this worktree, planning and evidence, runtime RTL verification, combined tests, review, integration and hosted CI.
- Golden writer: only DiagnosticsMeasurementsScreenshotTest.health_rtl.png in RIPDPI-ar-health-golden-20261015. The user explicitly approved this path after review of the corrected cache-misses label and its line wrap.

Every writer is isolated, preserves other work and waits for planning validation before production edits. Only the explicitly approved health_rtl.png golden may change. Other golden fixtures and quality baselines remain read-only. Main integration and push are explicitly authorized. RDS layout remains unchanged; this change corrects content at existing presentation boundaries. Native-speaker approval and physical-device acceptance are separate from source validation.
