# Task Board — RIPDPI

This folder is the Obsidian vault for RIPDPI task management.

## Structure

| File / Folder | Purpose |
| --- | --- |
| `active.md` | Live Obsidian Tasks query — doing + review |
| `backlog.md` | Live Obsidian Tasks query — backlog |
| `blocked.md` | Live Obsidian Tasks query — blocked |
| `epics.md` | Live Obsidian Tasks query — all epics |
| `dashboard.md` | Full query hub — all statuses |
| `board.md` | Kanban board — visual swim-lane view |
| `issues/<slug>.md` | One file per task/epic — source of truth |
| `templates/new-task.md` | Templater template for new tasks |
| `templates/new-epic.md` | Templater template for new epics |
| `views/*.base` | Obsidian Bases structured views |

## Canonical task line

Each `issues/<slug>.md` file contains exactly one `- [ ]` line:

```md
- [ ] #task <title> #repo/RIPDPI #area/<area> #status/<status> <priority>
```

Epic notes use the same format with the `#area/epic` tag:

```md
- [ ] #task Epic — <title> #repo/RIPDPI #area/epic #status/<status> <priority>
```

## Allowed statuses

`#status/backlog` · `#status/todo` · `#status/doing` · `#status/review` · `#status/blocked` · `#status/done` · `#status/dropped`

## Priority markers

`🔺` critical · `⏫` high · `🔼` medium · `🔽` low

## Canonical area enum

`engine` · `rust-native` · `diagnostics` · `transport` · `outbound` · `dns` · `routing` · `vpn` · `proxy` · `relay` · `android` · `ui` · `data` · `service` · `testing` · `ci` · `epic`

## YAML frontmatter schema

```yaml
---
title: Imperative task title
type: task            # task | epic
status: doing         # backlog | todo | doing | review | blocked | done | dropped
area: diagnostics     # canonical area enum above
priority: high        # critical | high | medium | low
owner: Role name
parent: epic-slug     # slug of parent epic, or null
blocks: []            # list of task slugs this task blocks
blocked_by: []        # list of task slugs blocking this task
created: YYYY-MM-DD
updated: YYYY-MM-DD
---
```

## Lifecycle

1. **New task** — run Templater: "Create new note from template" → `new-task.md` (or `new-epic.md`). Fill prompts. File lands in `issues/` with a kebab-case filename matching the title.
2. **Status transition** — update `status:` in frontmatter AND change the `#status/*` tag in the canonical `- [ ]` line. Always update `updated:`.
3. **Done** — delete `issues/<slug>.md`. Git history is the audit trail: `git log -- docs/tasks/issues/<slug>.md`.

## Open in Obsidian

Open the **repo root** (`/path/to/RIPDPI`) as your Obsidian vault. The root `.obsidian/` contains the Tasks plugin config with global filter `#task`.
