---
id: UIX-1791818839787311
title: Fix audited Chinese localization
kind: bug
status: doing
area: ui
priority: high
owner: Localization team
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-audited-chinese-localization
created: 2026-10-12
updated: 2026-10-12
---

## Goal

Chinese users can read every audited warning, action, and diagnostic result in the selected app language.

## Acceptance criteria

Resource and presentation regression tests cover the audit findings. All ten locales retain key and format parity. App/service lint, app unit tests, static analysis, and Chinese screenshot verification pass before main integration and push.

## Ownership

- Resource writer: all locale XML sets, translation manifest, localization resource regression script, and provenance ledger. Separate worktree `RIPDPI-zh-resources-20261012`.
- Diagnostics writer: diagnostic tool mappers, RKN result presentation, tool card captions, and their Kotlin tests. Separate worktree `RIPDPI-zh-diagnostics-20261012`.
- History writer: history connection-state presentation, remembered-network locale dates, and their Kotlin tests. Separate worktree `RIPDPI-zh-history-20261012`.
- Coordinator: planning, integration, validation evidence, and scoped commits in `RIPDPI-zh-localization-20261012`.
- Locale resources and manifest are serialized to the resource writer. Code writers send resource key requests and never edit XML. No writer changes golden fixtures or baselines.
