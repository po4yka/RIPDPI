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

- `docs/tasks/backlog.md` — backlog items by area
- `docs/tasks/active.md` — in-progress and review tasks
- `docs/tasks/blocked.md` — blocked tasks with reasons
- `docs/tasks/dashboard.md` — Obsidian Tasks query hub

Canonical task syntax:

```md
- [ ] #task <imperative title> #repo/RIPDPI #area/<area> #status/<status> <priority>
```

Invoke the `repo-task-board` skill when the user mentions: roadmap, TODO, backlog, Kanban, task board, sprint, blocked work, or agent-ready work.