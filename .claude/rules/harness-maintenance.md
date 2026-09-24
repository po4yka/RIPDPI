---
paths:
  - "AGENTS.md"
  - "CLAUDE.md"
  - "**/AGENTS.md"
  - "**/CLAUDE.md"
  - ".agents/**"
  - ".claude/**"
  - ".codex/**"
  - ".github/skills/**"
  - ".mcp.json"
  - "scripts/ci/check_harness_*.py"
  - "scripts/tests/test_agent_hooks.py"
---

# Harness maintenance

Applies when editing agent instructions, skills, subagents, rules, hooks, or their CI. Validate every such change with `just harness-check`; the blocking `harness-validation` workflow runs the same checks.

## Layers

- `AGENTS.md` is read by Codex directly and by Claude Code through the `@AGENTS.md` import in `CLAUDE.md`. It holds project facts, boundaries, and pointers that every task needs, and must stay under Codex's 32 KiB `project_doc_max_bytes` default. Put a line there only if removing it would cause a recurring repository-specific mistake.
- `CLAUDE.md` starts with `@AGENTS.md` (CI-checked) and adds only Claude Code-specific notes.
- `.claude/rules/*.md` hold file-area guidance. Claude Code reads only the `paths` frontmatter key (CI rejects any other key) and loads a rule when a matching file is read. Codex does not load these; the routing table in `AGENTS.md` points it at them, so add a row there for every new rule.
- `.agents/skills/` is the canonical skill root and the location Codex scans. `.claude/skills/` (Claude Code) and `.github/skills/` (GitHub Copilot) are symlink mirrors; CI requires every skill to appear in both.
- Deterministic requirements belong in hooks or CI, not prose. `.claude/settings.json` and `.codex/hooks.json` stay equivalent (only the edit-tool matcher differs: Claude Code uses `Edit|Write`, Codex uses `apply_patch`); `scripts/tests/test_agent_hooks.py` checks both.

## Skills

- The description is the routing interface both runtimes show before loading the body, and Codex truncates descriptions when the catalog is large. Write `<what it provides>. Use when <specific trigger>.`, add `Not for <neighbor> (use <skill>).` only for a real competing skill, keep it near 200 characters (CI rejects more than 250 for project skills), and front-load safety clauses.
- Keep `SKILL.md` to the decision logic needed when the skill activates; move long tables, examples, and rare branches into `references/` and point to them where they become relevant. Put deterministic steps in `scripts/`.
- Prefer pointers to the canonical source (`gradle/libs.versions.toml`, a registry file, a `grep` that finds the current value) over copied versions, counts, enum lists, or line numbers, which go stale.
- A manual-only skill sets `disable-model-invocation: true` and ships `agents/openai.yaml` with `policy.allow_implicit_invocation: false`; CI requires the two to agree.
- Frontmatter keys must be valid Claude Code keys (CI-checked); `user-invocable` defaults to true and is spelled with a `c`.
- Skills listed in `tools/tasking/generated-assets.lock.json` (`sdd`, `mdtask*`, `openspec-*`) are generated and hash-locked; change them only by regenerating through their owning tool.
- Centralized Rust skills are symlinks into `.agents/vendor/rust-skills`; never copy or edit them here. Exposure is deliberate: every upstream skill is either symlinked or listed with its reason in `EXCLUDED_VENDOR_SKILLS` in `scripts/ci/check_harness_manifests.py`. After a submodule bump, decide for each new skill; re-expose an excluded one when the repository gains its surface.

## Subagents

- Add a subagent only when separate context helps: independent review or audit, long test or benchmark runs, broad read-only investigation, or isolated writes. Persona agents that restate general engineering practice do not earn their context cost.
- Every `.claude/agents/<name>.md` has a `.codex/agents/<name>.toml` counterpart with the same intent (CI-checked). Codex cannot preload skills, so the counterpart's `developer_instructions` must name each skill the Claude agent lists under `skills:` (CI-checked).
- Omit `model` in Claude agents so they inherit the session model; a pinned value must be a Claude Code alias or `claude-*` ID (CI-checked). Pin a Codex `model` only with a stated reason.
- Every Codex agent declares `sandbox_mode` (CI-checked): `read-only` for auditors and reviewers, `workspace-write` for agents that build or write artifacts. Claude audit agents get no `Write`/`Edit` tools. Agents that write tracked files (`golden-blesser`, `native-verifier`, `ripdpi-vault-sync`) declare `isolation: worktree` in Claude Code.
- Descriptions follow the skill description rule: what the agent does and when to delegate to it.
