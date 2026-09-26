# Change: Preserve domain bypass rules on invalid drafts

Task ID: `UIX-1790432223307662`

## Why

When every line in a domain bypass draft is invalid, Save removes the existing rule and reports that the list was cleared. The user did not request a clear operation. This can silently change active routing.

## What Changes

- Save leaves the managed rule unchanged when the draft has errors and no valid entries.
- An empty draft still clears the rule. A mixed draft still saves its valid entries.
- The editor does not offer Save for a draft with only invalid entries.

## Capabilities

### New Capabilities

- `domain-bypass-save`: The domain bypass editor distinguishes an invalid draft from an empty list.

### Modified Capabilities

- None.

## Impact

- Affects `:app` and `:core:data` domain bypass save behavior and tests.
- No schema, dependency, network, or native contract changes.
