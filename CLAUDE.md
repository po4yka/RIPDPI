@AGENTS.md

# Claude Code

- Rules in `.claude/rules/` load automatically when you read files matching their `paths`; there is no need to open them manually.
- Project subagents in `.claude/agents/` inherit the session model. Run any subagent that edits tracked files with worktree isolation.
- Personal permissions, plugins, and MCP servers go in the gitignored `.claude/settings.local.json` (template: `.claude/settings.example.json`). Never overwrite the committed `.claude/settings.json`; it carries the repository hooks.
