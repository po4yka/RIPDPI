# Change: Remove stable relay profile identifiers from diagnostics archives

Task ID: `DGN-1790430871310010`

## Why

The archive declares `redacted_unlinkable_v2`, but relay health records export a stable SHA-256 profile token. Two archives from the same profile can therefore be linked. Related attempt and event IDs can also contain or derive from that token.

## What Changes

- Relay health records retain profile correlation only within one archive.
- Native event records omit stable relay profile, attempt, and derived event identifiers.
- BREAKING: Consumers cannot use relay profile or attempt identifiers to join separate archives.

## Capabilities

### New Capabilities

- `diagnostics/relay-archive-unlinkability`: Relay health identifiers in a diagnostics archive cannot link separate exports.

### Modified Capabilities

- None.

## Impact

- Affects `:core:diagnostics` archive redaction and relay health JSONL export. No wire, storage, or service contract changes.
