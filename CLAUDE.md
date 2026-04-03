# CLAUDE.md -- RIPDPI

See AGENTS.md for full project context.

## Rules

- **Never extend baselines** (detekt baselines, LoC baselines, lint baselines) to suppress new violations. Always fix the underlying issue -- refactor long files, resolve detekt findings, etc. Baselines exist only for legacy debt; new code must not add to them.
- **Non-rooted Android only** -- all technologies, techniques, and dependencies used in the project must work on non-rooted Android devices. Do not introduce anything that requires root access.
