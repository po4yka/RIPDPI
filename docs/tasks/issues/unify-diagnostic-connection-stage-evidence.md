---
id: DGN-1791523992124084
title: Unify diagnostic connection stage evidence
kind: feature
status: review
area: diagnostics
priority: high
owner: stage-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: connection-stage-scale
created: 2026-10-09
updated: 2026-10-09
status_detail: Implementation and local gates passed; remote CI, device and installable artifact evidence remain pending.
---

## Goal

Show consistent evidence-based connection stages in diagnostic results, history, live transfers and text exports.

## Acceptance criteria

- Conservative mappings preserve independent attempts and unknown states.
- UI and text exports use one stage model with ten locales.
- Focused tests, visual checks, static analysis and locale lint pass.

## Ownership

- Core writer: new ConnectionStage Kotlin files and tests, isolated worktree.
- UI writer: app source, tests and ten locale sets, isolated worktree.
- Integration writer: export integration, docs/task records and serialized Gradle/Git gates.
