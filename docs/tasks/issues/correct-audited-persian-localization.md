---
id: UIX-1792059951283875
title: Correct audited Persian localization
kind: bug
status: review
area: ui
priority: high
owner: Localization maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-audited-persian-localization
created: 2026-10-15
updated: 2026-10-15
status_detail: All confirmed Persian corrections are on main; local gates and all seven source CI workflows pass.
---

## Goal

Correct all 121 active Persian resource entries and six shared presentation groups confirmed at source a6b286e21ebf52d4d8901c63481b304122a7556d.

## Acceptance criteria

Preserve English meaning, security warnings and recovery actions in all audited Persian entries. Resolve Approach and Strategy report presentation and normal Home results through the selected app language, including retained EN to Persian to EN. Preserve raw producer records, storage/export formats, identifiers, addresses and custom text. Keep confidence status colors and protocol grouping correct after localization. Add each new resource key in all ten locales in the same source commit. Observe regression RED and GREEN, unfiltered Full and Simple app tests, both app lint variants, service lint, static analysis, screenshots, architecture health and locked Cargo metadata. Rebase, integrate main, push and wait for green hosted CI.

## Ownership

- Resource writer: RIPDPI-fa-resources-20261015, all app/service locale XML sets, the 121 audited Persian entries, all new Home/Strategy keys in all ten locales, resource regressions, translation export manifest and localization provenance. No other writer edits resource XML.
- Home writer: RIPDPI-fa-home-20261015, MainHomeDiagnosticsUiState, app-only Home presentation helpers, HomeDiagnosticsUiModels, the confidence rendering in HomeAnalysisBottomSheets and new Home regression tests. No core producer, storage, schema, export, service or network edits. Send a resource manifest to the resource writer first.
- Strategy writer: RIPDPI-fa-strategy-20261015, ui/diagnostics/DiagnosticsUiStrategy helpers, activities/DiagnosticsUiStrategyAuditSupport and DiagnosticsUiStrategyPresenters, plus new Strategy and Approach presentation tests. Keep raw protocol grouping separate from translated labels. No Home or resource edits. Send a resource manifest first.
- Integration owner: RIPDPI-fa-localization-20261015, this task and specification, independent review, combined tests and renders, patch integration, main publication and CI evidence.

Each writer has an isolated worktree, preserves other work and waits for planning validation before production edits. The resource writer serializes all locale sets. Kotlin workers submit tests before implementation. The integration worktree retains an unchanged test-only baseline until RED is observed; isolated workers may implement while that baseline waits for the shared build gate. Fixes enter the combined tree only after RED. No golden fixture or quality baseline update is authorized. Existing RDS layout and tokens remain unchanged; this task corrects content at current display boundaries. Dedicated stageAnnouncement and stage.headline fields have no current UI consumer and are excluded. No dependency, JNI, protobuf, wire or storage contract change is planned. Main integration and push are explicitly authorized. Native-speaker and physical-device acceptance are separate evidence levels.
