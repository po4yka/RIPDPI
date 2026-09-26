---
id: UIX-1790432223307662
title: Keep bypass rule when all draft domains are invalid
kind: bug
status: doing
area: ui
priority: high
owner: App audit
parent: null
blocked_by: []
spec_mode: required
openspec_change: preserve-domain-bypass-on-invalid-draft
created: 2026-09-26
updated: 2026-09-26
---

## Goal

An invalid-only domain bypass draft cannot remove a saved bypass rule.

## Acceptance criteria

- An invalid-only draft leaves the existing managed rule unchanged and does not show a saved or cleared confirmation.
- Save is unavailable before the saved rule finishes loading; an early Save cannot clear it.
- A blank draft still clears the managed rule. A mixed valid and invalid draft still saves valid entries.
- Focused repository and app tests pass. App lint reports no new issues.

## Ownership

- The `audit/app-module-20260926` worktree owns `DomainBypassListViewModel.kt`, `DomainBypassListScreen.kt`, `RuleRepository.kt`, their direct tests, and this change's task and OpenSpec files.
- No locale, golden, baseline, registry, or schema files are changed.
