---
id: DGN-1791484787190798
title: Expose diagnostic measurement limits
kind: bug
status: doing
area: diagnostics
priority: medium
owner: Root presentation writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: diagnostic-measurement-limits
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Expose control validation and measurement limits in local and shared reports.

## Acceptance criteria

Regression tests cover tri-state controls, evidence export, and interface MTU labels. Android lint validates all locales.

## Ownership

Root owns summary renderer/projector, app diagnosis presentation, and all locale resources. Metadata worker owns snapshot models/mapper; Home worker owns late augmentation scope; controls worker owns diagnosis classification. All workers use separate worktrees. No shared schema or golden edits.
