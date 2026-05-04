# CLAUDE.md -- RIPDPI

See AGENTS.md for project architecture, build commands, modules, native code, CI/CD, agent skills, and Kotlin anti-patterns. Global guardrails (simplicity, root-cause focus, surgical changes) live in `~/.claude/CLAUDE.md` and are not repeated here.

## Project rules

- **Never extend baselines** (detekt, LoC, lint). Fix the underlying violation -- baselines exist only for legacy debt. Enforced at the hook level by `PreToolUse` blocking edits to `*baseline*` files; do not work around it.
- **Non-rooted Android baseline** -- the app must fully function on non-rooted devices. Root-only features (`ripdpi-root-helper`, `FakeRst`, `MultiDisorder`, `IpFrag2`) are opt-in behind the `root_mode_enabled` setting and must degrade gracefully when root is unavailable.
- **No backend server** -- all features work offline and locally. Do not design features that require an API endpoint or remote service operated by the project. External data uses static files on GitHub or bundled assets; user data never leaves the device unless the user explicitly exports it.
- **Goal-driven execution** -- before implementing, convert each task into verifiable success criteria (test name, metric delta, UI render) and verify each before reporting completion. For multi-step work, state a brief per-step plan with the verification command/test name. Ask for clarification when criteria are ambiguous rather than guessing.

## Project-specific reinforcement

- Surface ambiguity early: a `DesyncMode` without documented activation, an undocumented JNI contract, a missing schema migration, an unclear protobuf field number -- name it, do not guess.
- Reproduce before fixing: a packet-smoke scenario, a `cargo nextest` test, or a Roborazzi baseline is the artifact you change; the source edit follows.
- Removing custom detekt rules, lint baselines, or other quality gates is out of scope unless the user explicitly asks for it.
---

## Task Board

This repository uses Obsidian Tasks-compatible Markdown task lines as the canonical task system.
Use the `repo-task-board` skill for all task-related operations.

Canonical files:

- `docs/tasks/issues/<slug>.md` — **source of truth** — one note per task/epic (YAML frontmatter + canonical `- [ ]` line + spec)
- `docs/tasks/active.md` — Obsidian Tasks query view (`#status/doing`, `#status/review`)
- `docs/tasks/backlog.md` — Obsidian Tasks query view (`#status/backlog`)
- `docs/tasks/blocked.md` — Obsidian Tasks query view (`#status/blocked`)
- `docs/tasks/epics.md` — Obsidian Tasks query view (`#area/epic`)
- `docs/tasks/dashboard.md` — Obsidian Tasks query hub + Bases view links
- `docs/tasks/board.md` — Kanban board (visual layer; source of truth is `issues/`)

Canonical task syntax (lives inside `docs/tasks/issues/<slug>.md`):

```md
- [ ] #task <imperative title> #repo/RIPDPI #area/<area> #status/<status> <priority>
```

Per-task note YAML frontmatter:

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
blocks: []
blocked_by: []
created: YYYY-MM-DD
updated: YYYY-MM-DD
---
```

Lifecycle: create via Templater template → transitions update `status:` + `#status/*` tag → delete file on close (git history is the audit trail). Do NOT add task lines to `active.md`, `backlog.md`, `blocked.md`, or `epics.md` — those are query-only views.

Invoke the `repo-task-board` skill when the user mentions: roadmap, TODO, backlog, Kanban, task board, sprint, blocked work, or agent-ready work.