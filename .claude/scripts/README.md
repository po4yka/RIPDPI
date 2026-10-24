# Agent hooks

RIPDPI commits equivalent hook manifests for both supported coding-agent runtimes: `.claude/settings.json` for Claude Code and `.codex/hooks.json` for Codex. Per-developer Claude permissions, plugins, and MCP servers belong in the gitignored `.claude/settings.local.json`; `.claude/settings.example.json` is an optional template.

Codex loads project hooks only after the project is trusted and the hook configuration hash is approved. Open `/hooks` in Codex to inspect the active merged configuration and approve repository changes. Claude Code loads the committed project settings once the workspace is trusted and merges local settings on top. Hook entries merge across settings layers, so a local file cannot remove a committed hook; CI remains the enforcement boundary.

## Enforcement

The `PreToolUse` hook calls `.agents/hooks/pre_tool_policy.py`. It normalizes both structured `Edit`/`Write` inputs and textual `apply_patch` payloads, then denies edits to the repository's quality baselines. The denial uses the shared `hookSpecificOutput.permissionDecision = "deny"` protocol and Codex's explicit top-level `decision = "block"` field. Claude Code edits arrive as `Edit`/`Write`; Codex edits arrive as `apply_patch`, so the Codex matcher is `Edit|Write|apply_patch` and the Claude matcher is `Edit|Write`.

The `SubagentStop` hook calls `subagent-stop-audit-log.sh`. It records sub-agent completion metadata under the ignored `.claude/logs/` directory and flags audit agents that return no recognizable conclusion. It never feeds back into the sub-agent. Set `RIPDPI_SUBAGENT_HOOKS=off` to disable this local observability.

Build, format, and lint checks are not agent hooks. Agents run the narrowest check after a batch of edits. Lefthook's pre-commit runs `cargo fmt --check` and clippy for the crates that own the staged Rust files; CI runs workspace-wide clippy for dependents. Two earlier hooks were removed: a turn-end hook that repeated these gates added minutes per turn, and a post-edit crate compile check ran after every Rust edit instead of once per batch. In recorded sessions, 13 of its 24 failure reports were timeouts or mid-batch states.

## Validation

Run the complete harness gate (the same checks as the blocking `harness-validation` GitHub Actions job):

```bash
just harness-check
```
