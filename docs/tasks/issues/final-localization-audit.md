---
id: UIX-1792080010916230
title: Fix final localization audit regressions
kind: bug
status: review
area: ui
priority: high
owner: Localization remediation
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-15
updated: 2026-10-15
spec_reason: regression-tested-single-module
---

## Goal

Correct all 17 confirmed groups in the final localization audit at 3c1df4f9. Keep raw diagnostic records, exports, URIs, and user names unchanged. All code changes stay in the app presentation module. This is a set of regression fixes, with no new feature or shared schema change.

## Acceptance criteria

- Verify each group F01 through F17 with a test or a documented check.
- Keep keys, types, format arguments, and native language names in parity across all 10 locales.
- Run app unit tests for Full and Simple, app and service lint, locale checks, and architecture checks.
- Inspect affected UI text and language changes. Do not update goldens without approval.
- Integrate into main, push, and observe green CI for the published SHA.

## Ownership

- Root: integration, F10 DNS composition, F11 through F13 counts, and task records.
- Resource worker: all locale XML and generated translation exports. This is the only locale writer.
- Diagnostics worker: F02 through F06 and F14; app diagnostics and detection presentation code and its tests.
- State worker: F01, F15, F16, and F17; retained UI state, export errors, QuickTile, and their tests.
- Each writer uses a separate worktree. Resource keys are agreed before integration. No worker changes goldens, baselines, shared schemas, or dependency files.

## Evidence

- Input audit: /tmp/ripdpi-final-i18n-audit-20261015/audit.md and confirmed-findings.json.
- Source corrections and observed validation: docs/audits/2026-10-15-localization-remediation.md.
- Local evidence: /tmp/ripdpi-final-remediation-20261015/. Hosted CI is pending publication.
