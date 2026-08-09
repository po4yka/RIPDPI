---
id: CIC-1786271703268565
title: Refresh and migrate the repository backlog
kind: chore
status: doing
area: ci
priority: high
owner: Tasking maintainer
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-08-09
updated: 2026-08-09
spec_reason: tooling-only
---

## Goal

Leave one evidence-backed backlog in the strict task/OpenSpec system, with completed and obsolete legacy entries removed through the terminal-history protocol.

## Acceptance criteria

- Every surviving actionable item from the current board, `ROADMAP.md`, and recovery task reports is represented by one strict portfolio task and one valid execution file.
- Completed and obsolete items are classified from code, tests, Git history, and external-run evidence; terminal tasks are committed before purge.
- Task relationships, statuses, priorities, ownership, OpenSpec classification, step progress, and generated board match the observed repository state.
- `./taskctl validate --base <pre-refresh-main>`, tasking contract tests, OpenSpec strict validation, legal review, and independent final review pass on the rebased combined tree.
