---
name: repo-task-board
description: Use when creating, updating, triaging, or completing repository tasks stored as Obsidian Tasks Markdown lines with #task, #status/*, #repo/RIPDPI, and #area/* tags. Use for ROADMAP.md, docs/tasks/*.md, Kanban board maintenance, backlog grooming, and agent-ready implementation planning.
---

# Repository Task Board — RIPDPI

This repository uses Obsidian Tasks-compatible Markdown checkboxes as the canonical task system.

## Canonical task line

```md
- [ ] #task <imperative task title> #repo/RIPDPI #area/<area> #status/<status> <priority>
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

- `docs/tasks/issues/<slug>.md` — **source of truth** — one note per task/epic (YAML frontmatter + canonical `- [ ]` line + spec)
- `docs/tasks/active.md` — query view for `#status/doing` and `#status/review`
- `docs/tasks/backlog.md` — query view for `#status/backlog`
- `docs/tasks/blocked.md` — query view for `#status/blocked`
- `docs/tasks/epics.md` — query view for `#area/epic`
- `docs/tasks/dashboard.md` — Obsidian Tasks query hub + Bases view links
- `docs/tasks/board.md` — Kanban board (visual layer; source of truth is `issues/`)

## Per-task notes

Each task or epic lives in `docs/tasks/issues/<slug>.md`. This is the source of truth.

```yaml
---
title: Imperative task title
type: task            # task | epic
status: doing         # backlog | todo | doing | review | blocked | done | dropped
area: diagnostics     # engine | rust-native | diagnostics | transport | outbound | dns |
                      # routing | vpn | proxy | relay | android | ui | data | service |
                      # testing | ci | epic
priority: high        # critical | high | medium | low
owner: Role name
parent: epic-slug     # slug of parent epic, or null
blocks: []            # list of task slugs this task blocks
blocked_by: []        # list of task slugs blocking this task
created: YYYY-MM-DD
updated: YYYY-MM-DD
---
```

Epic notes (`type: epic`) use `#area/epic` on the canonical line and include `## Goal / ## Why now / ## Key decisions / ## Scope / ## Ship definition` sections. Child tasks reference their parent via `parent: <epic-slug>`.

Lifecycle: create via Templater → update `status:` + `#status/*` tag on transition → delete file on close (git history is the audit trail). Do NOT add task lines to `active.md`, `backlog.md`, `blocked.md`, or `epics.md` — those are query-only views.

## Rules

1. Preserve valid Obsidian Tasks syntax.
2. Never create duplicate task lines for the same work.
3. Prefer editing the existing `issues/<slug>.md` note over creating a new one.
4. Keep task titles imperative and implementation-oriented.
5. Exactly one `#status/*` tag per task; remove the previous one when transitioning.
6. Add `#blocked` alongside `#status/blocked`; add a blocking reason in the body.
7. When completing: change `[ ]` to `[x]`, set `#status/done`, add `✅ YYYY-MM-DD`, then delete the file.
8. RIPDPI ROADMAP.md is forward-looking only — do not add completed work to it.
9. Do not change unrelated prose, code, or other sections.

## Task creation workflow

1. Search `docs/tasks/issues/` for similar tasks (the slug should be self-explanatory).
2. If a similar task exists, update it instead of duplicating.
3. Use Templater: "Create new note from template" → `new-task.md` (or `new-epic.md`). File lands in `issues/` automatically.
4. Fill prompts: title, area, priority, owner, parent epic slug (or blank).
5. The Templater template writes the canonical `- [ ]` line into the note; do not add it to the bucket files.

## Implementation workflow

1. Find candidate: `#task #repo/RIPDPI #status/todo` or `#status/backlog`, no `#blocked`.
2. Update `status: doing` in frontmatter and `#status/doing` in the canonical line. Update `updated:`.
3. Implement, run tests per RIPDPI CLAUDE.md verification rules.
4. Update `status: review` and `#status/review`.
5. Add a `## Work log` section to the note: changed files, test run, remaining risk.
6. Mark `#status/done` only when all acceptance checks pass, then delete the file.
