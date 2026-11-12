---
id: ENG-1794499735949864
title: Repair DPI-05 fake split contracts
kind: bug
status: doing
area: engine
priority: high
owner: DPI-05 repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi05-fake-split-repairs
created: 2026-11-12
updated: 2026-11-12
---

## Goal

Repair six confirmed DPI-05 findings without changing accepted settings or permission scope.

## Acceptance criteria

F1-F6 have regression coverage, independent review and passing relevant gates. Each finding has a separate commit, integrated into main and pushed to origin/main.

## Parallel ownership

Exact file ownership is recorded in openspec/changes/dpi05-fake-split-repairs/design.md. raw_repairs owns raw/progress paths; runtime_repairs owns fake-family/emissions and outcome tests; wire_oracle owns acceptance scripts and test-only fixtures. Root owns lifecycle, generated API snapshots, combined review and integration. Cargo/Gradle versions, locales, golden fixtures and helper wire schemas remain outside scope.
