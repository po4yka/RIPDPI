---
name: repo-task-board
description: Use when creating, updating, triaging, or completing repository tasks stored as Obsidian Tasks Markdown lines with #task, #status/*, #repo/RIPDPI, and #area/* tags. Use for ROADMAP.md, docs/tasks/*.md, Kanban board maintenance, backlog grooming, and agent-ready implementation planning.
---

# Repository Task Board — RIPDPI

This repository uses Obsidian Tasks-compatible Markdown checkboxes as the canonical task system.

## Canonical task line

```md
- [ ] #task <imperative task title> #repo/RIPDPI #area/<area> #status/<status> <priority> [paperclip:POY-N]
```

## Allowed statuses

- `#status/backlog`
- `#status/todo`
- `#status/doing`
- `#status/review`
- `#status/blocked`
- `#status/done`
- `#status/dropped`

## Priority markers

- `🔺` critical  ·  `⏫` high  ·  `🔼` medium  ·  `🔽` low

## Canonical files

- `docs/tasks/backlog.md` — `#status/backlog` items by area
- `docs/tasks/active.md` — `#status/todo`, `#status/doing`, `#status/review`
- `docs/tasks/blocked.md` — `#status/blocked` items with reason
- `docs/tasks/epics.md` — strategic epics (parent issues)
- `docs/tasks/dashboard.md` — Obsidian Tasks query hub

## Rules

1. Preserve valid Obsidian Tasks syntax.
2. Never create duplicate task lines for the same work.
3. Prefer editing the existing task line over adding a new one.
4. Keep task titles imperative and implementation-oriented.
5. Exactly one `#status/*` tag per task; remove the previous one when transitioning.
6. Add `#blocked` alongside `#status/blocked`; add an indented reason below.
7. When completing: change `[ ]` to `[x]`, set `#status/done`, add `✅ YYYY-MM-DD`.
8. RIPDPI ROADMAP.md is forward-looking only — do not add completed work to it.
9. Preserve `[paperclip:POY-N]` cross-reference tokens on existing tasks.
10. Do not change unrelated prose, code, or other sections.

## Task creation workflow

1. Search `docs/tasks/` for similar tasks.
2. If similar task exists, update it instead of duplicating.
3. Choose the right file: `backlog.md` for new work, `active.md` if starting now, `blocked.md` if blocked.
4. Assign: `#repo/RIPDPI`, `#area/<area>`, one `#status/*`, priority marker.
5. Add context as indented bullets only when acceptance criteria or blocking reason is non-obvious.

## Implementation workflow

1. Find candidate: `#task #repo/RIPDPI #status/todo` or `#status/backlog`, no `#blocked`.
2. Move task to `#status/doing`.
3. Implement, run tests per RIPDPI CLAUDE.md verification rules.
4. Move to `#status/review`.
5. Add work-log bullet: changed files, test run, remaining risk.
6. Mark `#status/done` only when all acceptance checks pass.
