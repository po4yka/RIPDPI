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
3. **Done / dropped** — delete `issues/<slug>.md`. Git history is the audit trail:
   `git log -- docs/tasks/issues/<slug>.md`.

## Regenerate `board.md`

`board.md` is derived from issue frontmatter. After changing statuses, regenerate it
(any equivalent script is fine):

```bash
cd docs/tasks/issues
for f in *.md; do
  awk -F': ' '
    /^status:/{s=$2} /^priority:/{p=$2} /^area:/{a=$2} /^title:/{sub(/^title: /,"");t=$0}
    END{printf "%s\t%s\t%s\t%s\t%s\n", s, p, a, t, FILENAME}
  ' "$f"
done | sort
```

Group the rows by status, sort by priority within each group, and write the result into
`docs/tasks/board.md` (the source-of-truth remains the per-issue files).
