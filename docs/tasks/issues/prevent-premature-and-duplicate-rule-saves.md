---
id: RTE-1790430175961042
title: Prevent premature and duplicate rule saves
kind: bug
status: review
area: routing
priority: high
owner: UI audit agent
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-26
updated: 2026-09-26
spec_reason: regression-tested-single-module
---

## Goal

Opening a routing rule never permits a save before its persisted fields load. Repeated Save taps create or update one rule once.

## Acceptance criteria

- A save before editor hydration leaves the existing rule unchanged.
- A repeated save while persistence is pending performs one write.
- The editor disables Save until hydration finishes and during persistence.
- Targeted editor tests and static analysis pass.

## Ownership

- UI audit agent owns `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/routes/RuleEditorViewModel.kt`, `RuleEditorScreen.kt`, and direct tests for this fix in `app/src/test/kotlin/com/poyka/ripdpi/ui/screens/routes/`.
- This task record and its execution file belong to the same agent. `docs/tasks/board.md` is a generated serialized lane; regenerate only for task validation and reconcile it before integration.
