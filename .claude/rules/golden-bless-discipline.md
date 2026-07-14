---
paths:
  - "**/tests/golden/**"
  - "**/src/test/resources/golden/**"
  - "**/src/test/screenshots/**"
  - "**/*golden*"
---

## Golden test bless discipline

Golden fixtures are compatibility boundaries. A failing golden is evidence to investigate, not permission to regenerate expected output.

### Authorization contract

- An agent must not run `RIPDPI_BLESS_GOLDENS=1`, a Roborazzi record task, or a repository bless script unless the user explicitly authorizes blessing in the current conversation and identifies the affected fixture family or path.
- After authorization, use the existing `golden-blesser` specialist in an isolated worktree, run only the narrow owning command, inspect the complete diff, and state why the behavioral contract changed.
- CI, hooks, unattended automation, and unrelated agentic loops must never bless fixtures.
- A human may bless locally and submit the reviewed fixture diff under the same rationale requirement.

The repository defines no approval slash command. Plain explicit user authorization is the gate; do not invent an entrypoint.

### Review sequence

1. Read the generated expected/actual/diff artifact.
2. If only volatile fields changed, repair scrubbing and rerun without blessing.
3. If the change is unexpected, fix the implementation regression.
4. If the user-authorized behavior intentionally changed, run the narrow bless command, inspect every fixture, and commit it with the rationale and relevant issue/spec reference.

The existing `golden-blesser` agent performs semantic, volatile-field, whitespace, and scrub-incomplete classification.

### Forbidden patterns

- Blessing merely to make a failed test pass.
- Bulk regeneration across unrelated fixture families.
- Blessing after a refactor without evidence that the contract intentionally changed.
- Copying Compose preview PNGs into Roborazzi or other golden locations.

### Cross-references

- `golden-blesser` agent for an authorized bless/review operation.
- `golden-test-management` skill for fixture ownership and commands.
- `llm-rust-prompts.md` for review of model-issued shell commands.
