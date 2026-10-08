---
id: DGN-1791484491005718
title: Scope diagnosis controls to measured protocols
kind: bug
status: doing
area: diagnostics
priority: medium
owner: controls
parent: null
blocked_by: []
spec_mode: required
openspec_change: diagnosis-protocol-evidence
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Scope diagnosis controls and descriptions to measured evidence.

## Acceptance criteria

Unit tests cover matching, unrelated, failed and absent controls; no generic SNI inference.

## Ownership

Controls worker: DiagnosticsFindingProjector.kt and native diagnosis modules/tests. Controls also owns active confirm-good native report and recommendation projection/tests. Root: UI/export/locales. No schema, lockfile or golden edits.
