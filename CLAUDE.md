# CLAUDE.md -- RIPDPI

See AGENTS.md for full project context.

## Rules

- **Never extend baselines** (detekt baselines, LoC baselines, lint baselines) to suppress new violations. Always fix the underlying issue -- refactor long files, resolve detekt findings, etc. Baselines exist only for legacy debt; new code must not add to them.
- **Non-rooted Android baseline** -- the app must fully function on non-rooted Android devices. Root-only features (ripdpi-root-helper, FakeRst, MultiDisorder, IpFrag2) are opt-in behind the `root_mode_enabled` setting and must degrade gracefully when root is unavailable.
- **No backend server** -- the app has no backend and will not have one. All features must work fully offline and locally. Do not design features that require a server, API endpoint, or remote service operated by the project. External data (host packs, community stats) must use static files hosted on GitHub or bundled in assets. User data never leaves the device unless the user explicitly exports/shares it.
- **Goal-driven execution** -- before implementing, convert the task into concrete, verifiable success criteria. State what "done" looks like (test passes, metric improves, UI renders correctly) and verify each criterion before reporting completion. When criteria are ambiguous, ask for clarification rather than guessing.
