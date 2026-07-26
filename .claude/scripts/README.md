# Agent hooks

RIPDPI commits equivalent hook manifests for both supported coding-agent runtimes: `.claude/settings.json` for Claude Code and `.codex/hooks.json` for Codex. Per-developer Claude permissions, plugins, and MCP servers belong in the gitignored `.claude/settings.local.json`; `.claude/settings.example.json` is an optional template.

Codex loads project hooks only after the project is trusted and the hook configuration hash is approved. Open `/hooks` in Codex to inspect the active merged configuration and approve repository changes. Claude Code loads the committed project settings automatically and merges local settings on top.

## Enforcement

The `PreToolUse` hook calls `.agents/hooks/pre_tool_policy.py`. It normalizes both structured `Edit`/`Write` inputs and textual `apply_patch` payloads, then denies edits to the repository's quality baselines. The denial uses the shared `hookSpecificOutput.permissionDecision = "deny"` protocol and Codex's explicit top-level `decision = "block"` field.

The `PostToolUse` hook calls `rust-postedit-check.sh`. After a Rust edit it locates the enclosing crate and runs `cargo check -p <crate> --locked --message-format=short`. The hook accepts both Claude Code file-path payloads and Codex `apply_patch` payloads. Set `RIPDPI_RUST_HOOKS=off` to disable Rust hooks temporarily.

The `Stop` hook calls `rust-stop-verify.sh`. When Rust files are dirty it runs `cargo fmt --all --check` and `cargo clippy --workspace --all-targets --locked -- -D warnings`. It is advisory by default because parallel sessions can expose unrelated dirty files; set `RIPDPI_RUST_HOOKS_STRICT=on` to make failures block completion.

The `SubagentStop` hook calls `subagent-stop-audit-log.sh`. It records sub-agent completion metadata under the ignored `.claude/logs/` directory and flags audit agents that return no recognizable conclusion. Set `RIPDPI_SUBAGENT_HOOKS=off` to disable this local observability.

## Validation

Run the hook smoke tests and the complete harness gate:

```bash
python3 scripts/tests/test_agent_hooks.py
python3 scripts/ci/check_harness_manifests.py
python3 scripts/ci/check_harness_links.py --strict
python3 scripts/ci/check_harness_policy.py
python3 scripts/ci/check_harness_cargo_locked.py
python3 scripts/ci/check_codex_skills_sync.py
bash scripts/ci/check-rules-drift.sh
```

The blocking `harness-validation` GitHub Actions job runs the same checks for every harness change.
