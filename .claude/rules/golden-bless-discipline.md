---
paths:
  - "**/tests/golden/**"
  - "**/src/test/resources/golden/**"
  - "**/src/test/screenshots/**"
  - "**/*golden*"
---

## Golden test bless discipline

Golden tests (under `tests/golden/`, `src/test/resources/golden/`, and the `golden-test-support` crate) capture the contract between Rust core, Kotlin layer, and instrumentation. The bless command (`RIPDPI_BLESS_GOLDENS=1`) regenerates them. LLM agents reflexively bless to "fix" failing tests, which silently destroys regression coverage. This rule blocks that pattern.

### Rule

`RIPDPI_BLESS_GOLDENS=1` is FORBIDDEN in any automated flow — sub-agent Bash command, hook script, CI job, agentic loop, or Claude Code session. The only valid invocations:

1. The user explicitly types `/approve-bless <golden_path>` or `/bless-goldens` in a session. The agent then runs the bless command for ONLY the specified paths.
2. A human reviewer runs the command locally with full context and commits the diff with a commit message explaining the intentional behavioral change.

Any commit that touches files under `tests/golden/` or `src/test/resources/golden/` MUST include in its message:
- One sentence stating WHY the contract changed (intentional behavioral change in <module>).
- A reference to the PR / issue / spec that mandates the change.

Without this, the commit is blocked by review.

### Scrub-incomplete detection

A `golden-drift-triager` sub-agent (separate from this rule) classifies golden diffs into:
- `whitespace-only` — accept silently.
- `volatile-field` — present in `tests/golden/scrub.json`; accept silently.
- `semantic-change` — requires human approval per above.
- `scrub-incomplete` — if a field changes the same way in >80% of goldens, the scrub list is stale. Propose extending `scrub.json`, not blessing.

### Forbidden anti-patterns

- "Tests fail. Let me rebless and continue." → REJECTED. Always investigate WHY the test fails first.
- Bulk re-bless across unrelated golden paths in one PR → REJECTED. Each behavioral change is its own commit.
- Bless after a refactor without a commit message rationale → REJECTED.

### Audit

A pre-commit hook (or PR check) should run:

```bash
git diff --cached --name-only | grep -E '(tests/golden/|src/test/resources/golden/)' && {
  msg=$(git log -1 --pretty=%B HEAD 2>/dev/null || cat .git/COMMIT_EDITMSG 2>/dev/null)
  echo "$msg" | grep -qE '(intentional|behavioral|approve-bless)' || {
    echo "BLOCKED: golden diff without intentional-change rationale in commit message"
    exit 2
  }
}
```

### Cross-references

- `golden-blesser` agent (existing) — handle blessing under human supervision.
- `llm-rust-prompts.md` — diff-acceptance gate for AI-generated bless attempts.
