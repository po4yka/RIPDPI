---
id: UIX-1791805113916673
title: Fix audited Russian localization defects
kind: bug
status: review
area: ui
priority: high
owner: Localization remediation
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-audited-russian-localization
created: 2026-10-12
updated: 2026-10-12
---

## Goal

Fix all confirmed EN-to-RU localization defects from the 2026-10-12 audit. Preserve protocol tokens, user data, and locale key parity.

## Acceptance criteria

- Russian warning, update, profile sharing, chain editor, Finalmask, remediation, device status, and diagnostic texts preserve the English meaning.
- Locale selection persists on API 27–32. Non-Activity strings use the selected app locale.
- Event counts and service session counts use locale-specific plural forms, including 1, 2, 5, 21, 22, and 25.
- DPI suite, routing protection, and diagnostic strategy presentation use localized resources.
- All ten locales contain each new resource with compatible placeholders.
- Locale parity, translation export, app/service tests, lint, static analysis, architecture checks, and Russian screenshot verification pass, or an observed blocker is recorded.
- Reviewed commits are integrated into main and pushed to origin, as requested by the user.

## Ownership

- Resource writer: all locale XML files in app and core/service, translation manifest, resource regression tests, localization provenance.
- Locale/runtime writer: app locale manifest and platform resolver, live event labels, service notification formatting, and their tests.
- Presentation writer: DPI suite, routing protection presentation, strategy signature presentation, and their tests.
- Integration writer: this portfolio record, OpenSpec artifacts, combined-tree fixes and validation, commits, main integration, and push.
- Every writer uses a separate worktree. Locale XML and the translation manifest have one writer. Other writers send requested resources to that writer before using them.
