# Task Board — RIPDPI

Plain-Markdown task tracker. No external app or plugin required — every file is readable
and editable as ordinary Markdown.

## Structure

| File / Folder | Purpose |
| --- | --- |
| `issues/<slug>.md` | **Source of truth** — one file per task/epic (YAML frontmatter + spec body) |
| `board.md` | Generated, read-only index of open issues grouped by status |
| `README.md` | This file — schema, conventions, and lifecycle |

## Per-issue file

Each task or epic is a single `issues/<slug>.md` file. The `<slug>` is the kebab-case
title. All state lives in the YAML frontmatter; the body holds the spec
(`## Goal`, `## Why now`, `## Scope`, `## Ship definition`, `## Work log`, …).

```yaml
---
title: Imperative task title
type: task            # task | epic
status: doing         # backlog | todo | doing | review | blocked | done | dropped
area: diagnostics     # canonical area enum (below)
priority: high        # critical | high | medium | low
owner: Role name
parent: epic-slug     # slug of parent epic, or null
blocks: []            # task slugs this task blocks
blocked_by: []        # task slugs blocking this task
created: YYYY-MM-DD
updated: YYYY-MM-DD
source_wiki_pages: []  # optional provenance for imported knowledge tasks
linked_task: null      # optional external/local task link
status_detail: null    # optional concise blocker/progress detail
status_note: null      # optional board-visible lifecycle note
---
```

Epic files use `type: epic` and `area: epic`, and add `## Goal / ## Why now /
## Key decisions / ## Scope / ## Ship definition`. Child tasks point back via
`parent: <epic-slug>`.

## Enums

- **Status:** `backlog` · `todo` · `doing` · `review` · `blocked` · `done` · `dropped`
- **Priority:** `critical` · `high` · `medium` · `low`
- **Area:** `engine` · `rust-native` · `diagnostics` · `transport` · `outbound` · `dns` ·
  `routing` · `vpn` · `proxy` · `relay` · `android` · `ui` · `data` · `service` ·
  `testing` · `ci` · `epic`

## Lifecycle

1. **New task** — copy an existing `issues/*.md`, rename to the new kebab-case slug, fill
   the frontmatter and spec body. `status: backlog` (or `todo`).
2. **Status transition** — update `status:` in the frontmatter and bump `updated:`.
   A `blocked` issue must either name in-repo blocker slugs in `blocked_by` or
   provide a non-empty `status_detail` for an external evidence/toolchain/hardware gate.
3. **Done / dropped** — delete `issues/<slug>.md`. Git history is the audit trail:
   `git log -- docs/tasks/issues/<slug>.md`.

## Regenerate `board.md`

`board.md` is derived deterministically from issue frontmatter. After changing
an issue, regenerate all five displayed columns (`Priority`, `Area`, `Issue`,
`Owner`, `Updated`):

```bash
python3 scripts/ci/generate_task_board.py
```

Verify without writing (suitable for local gates):

```bash
python3 scripts/ci/generate_task_board.py --check
```

The source of truth remains the per-issue files.
