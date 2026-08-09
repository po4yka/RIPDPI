---
name: repo-task-board
description: Use when creating, updating, triaging, executing, reviewing, or closing RIPDPI portfolio tasks, backlog items, bugs, epics, and OpenSpec-backed work.
---

# Repository task board

`docs/tasks/issues/*.md` is the portfolio source of truth. `docs/tasks/board.md` is generated. Execution lives in exactly one of:

- simple work: `docs/tasks/work/<TASK-ID>.md`;
- specification-driven work: `openspec/changes/<change>/tasks.md`.

Use only `./taskctl` for lifecycle operations:

- inspect: `list`, `show`, `ready`, `graph`;
- create/start/update: `new`, `start`, `transition`, `steps`;
- validate: `verify`, `validate`, `generate-board`;
- complete: `openspec archive`, then `close prepare --outcome done`, commit, and later `close purge` in a separate commit;
- drop: `close prepare --outcome dropped`, commit its receipts, then `openspec archive` and `close purge` before the deletion commit.

Stable IDs are area-prefixed with globally unique 16-digit numeric suffixes. Worktrees share a locked allocator reservation through the Git common directory; committed validation catches cross-clone collisions. `blocked_by` is canonical; reverse `blocks` is derived. OpenSpec is mandatory for feature/behavior/contract/high-risk work. Never hand-edit the generated board, use direct upstream archive, pass `--no-validate`, close work without evidence, or delete a record before its terminal-state commit exists.

Before parallel agents write, record ownership and serialized shared-file lanes in the portfolio task. All implementation work uses a dedicated worktree and follows `AGENTS.md`.
