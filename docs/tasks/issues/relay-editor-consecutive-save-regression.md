---
id: RLY-1790335469234890
title: Preserve relay profile identity across consecutive editor saves
kind: bug
status: review
area: relay
priority: high
owner: Android UI
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-25
updated: 2026-09-25
spec_reason: regression-tested-single-module
---

## Goal

Allow a second save in the same editor session after the first save completes while
later edits stay in the draft. Keep rejection of existing profile ID collisions and
stale profile or credential state. Keep the saved-state privacy contract test aligned
with the current recovery implementation.

## Acceptance criteria

- The in-flight edit concurrency test saves both requests and preserves the later edit.
- A new editor session cannot overwrite an existing relay profile by reusing its ID.
- The sensitive saved-state contract test passes without persisting credential drafts.
- Targeted app tests and static analysis pass.

## Ownership

This task owns app Config save/session support and the two failing app tests in the
`fix/relay-profile-ci-regressions` worktree. It does not edit golden fixtures,
locale resources, or the generated task board.
