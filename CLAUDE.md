@AGENTS.md

# Claude Code

Optional personal MCP configuration belongs in `.claude/settings.local.json` or user settings. Use `/memory`, `/skills`, `/agents`, `/hooks`, and `/permissions` to verify the effective harness before relying on it.

Path-scoped rules under `.claude/rules/` load automatically when matching files are read. Invoke task-specific skills explicitly when their trigger applies, and use worktree isolation for every write-capable subagent.
