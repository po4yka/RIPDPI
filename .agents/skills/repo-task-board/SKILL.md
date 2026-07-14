---
name: repo-task-board
description: Use when creating, updating, triaging, or completing repository tasks stored as plain-Markdown files under docs/tasks/issues/. Use for ROADMAP.md, docs/tasks/*.md, backlog grooming, status triage, and agent-ready implementation planning.
---

# Repository Task Board — RIPDPI

This repository tracks work as **plain-Markdown files** under `docs/tasks/`. No external
app or plugin is required. The source of truth is one file per task/epic; a generated
index (`docs/tasks/board.md`) gives a grouped overview.

## Canonical files

- `docs/tasks/issues/<slug>.md` — **source of truth** — one file per task/epic (YAML frontmatter + spec body)
- `docs/tasks/board.md` — generated, read-only index of open issues grouped by status
- `docs/tasks/README.md` — schema, enums, lifecycle, and the `docs/tasks/board.md` regeneration recipe

## Per-task file

Each task or epic lives in `docs/tasks/issues/<slug>.md`. `<slug>` is the kebab-case
title. All state lives in the YAML frontmatter; the body holds the spec.

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
blocks: []            # task slugs this task blocks
blocked_by: []        # task slugs blocking this task
created: YYYY-MM-DD
updated: YYYY-MM-DD
---
```

Epic files (`type: epic`, `area: epic`) include `## Goal / ## Why now / ## Key decisions /
## Scope / ## Ship definition` sections. Child tasks reference their parent via
`parent: <epic-slug>`.

## Enums

- **Status:** `backlog` · `todo` · `doing` · `review` · `blocked` · `done` · `dropped`
- **Priority:** `critical` · `high` · `medium` · `low`

## Rules

1. The frontmatter is the only state — keep `status:`, `priority:`, `area:`, `parent:` accurate.
2. Never create duplicate files for the same work; edit the existing `issues/<slug>.md`.
3. Keep task titles imperative and implementation-oriented.
4. Bump `updated:` on every status transition.
5. For blocked work, set `status: blocked`, list the blocker slug in `blocked_by:`, and explain in the body.
6. On completion or drop, set `status: done`/`dropped`, then **delete the file** (git history is the audit trail).
7. RIPDPI `ROADMAP.md` is forward-looking only — do not add completed work to it.
8. Do not hand-edit `docs/tasks/board.md`; regenerate it (see `docs/tasks/README.md` § Regenerate).
9. Do not change unrelated prose, code, or other sections.

## Task creation workflow

1. Search `docs/tasks/issues/` for similar tasks (the slug should be self-explanatory).
2. If a similar task exists, update it instead of duplicating.
3. Otherwise copy an existing `issues/*.md`, rename to the new kebab-case slug, and fill the frontmatter + spec body. Set `status: backlog` (or `todo`).
4. Regenerate `docs/tasks/board.md`.

## Implementation workflow

1. Find a candidate: an issue with `status: todo` or `status: backlog` and no unresolved `blocked_by:`.
2. Set `status: doing` and bump `updated:`.
3. Implement, run tests per RIPDPI CLAUDE.md verification rules.
4. Set `status: review` and add a `## Work log` section: changed files, test run, remaining risk.
5. Set `status: done` only when all acceptance checks pass, then delete the file.
6. Regenerate `docs/tasks/board.md` after status changes.
