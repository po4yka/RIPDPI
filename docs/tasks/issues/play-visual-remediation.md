---
id: UIX-1791637564152260
title: Fix Google Play visual audit findings with real app screens
kind: bug
status: done
area: ui
priority: high
owner: Play visual remediation
parent: null
blocked_by: []
spec_mode: required
openspec_change: play-visual-remediation
created: 2026-10-10
updated: 2026-10-10
status_detail: All eleven visual findings are closed. Source, device, layout, export and combined checks passed; implementation is pushed to main with verified remote SHA. Hosted CI remains separately observed.
closed_at: "2026-10-10T15:54:36Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: All eleven findings closed by independent review. Main implementation fefc897d73ec46edbc39c828f2e1b0e52fd2fe5e pushed and remote SHA verified. Local/device/artifact checks passed; archived verification records exact evidence. Hosted CI was running; Google Play upload and physical-device acceptance are outside scope.
---

## Goal

Resolve all eleven visual audit finding groups with real current feature screens and readable localized Google Play artwork.

## Acceptance criteria

All four REQ-PLAY requirements pass, all nine READMEs show readable materials, independent source and visual review find no remaining in-scope defects, and each finished unit is committed and pushed to main under the existing user authorization.

## Parallel ownership

Capture worker owns all app locale resources and minimal presentation mapping, capture scripts and tests, raw captures and source manifest, and docs/screenshots/ui. Layout worker owns renderer source, pinned font assets/license, capture.mjs, renderer README and generated marketing PNGs. Integration owner owns READMEs, factual generator guidance, task/spec records, combined gates and integration. Writers use separate worktrees. Reviewers are read-only. Locale sets have one writer.
