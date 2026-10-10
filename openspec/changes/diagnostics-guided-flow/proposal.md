# Change: Make diagnostics actionable and readable

Task ID: `UIX-1791534456996035`

## Why

The diagnostics flow hides run controls behind detailed catalogs. Full Home analysis has no visible stop action. Recommendation labels promise an operation that only opens an editor. Technical evidence is hard to compare and incomplete when copied.

## What Changes

- Provide quick checks, whole-run stop, and stable progress and profile identity.
- Keep full-run ownership through gaps between native sessions and reject unrelated scan admission until teardown completes.
- Use the selected user persona for progressive disclosure and concrete next actions.
- Show concise results before technical evidence. Preserve partial and unknown states.
- Include transfer and connection-stage evidence in copied results.
- Avoid cause claims that a single failed Blockcheck attempt cannot prove.

## Capabilities

### New Capabilities

- `diagnostics-guided-flow`: Run controls, evidence presentation, and next actions.

### Modified Capabilities

- None.

## Impact

- Android app UI, diagnostics actions and runtime ownership, Blockcheck classification, tests, and ten locales.
- No breaking wire, storage, dependency, or backend change.
