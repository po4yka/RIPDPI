# Change: Preserve AWG subscription profile identity

Task ID: `DAT-1791477854155125`

## Why

Subscription refresh creates a new AWG row each time. Server parameter rotation never reaches the profile with the local private key.

## What Changes

- Reuse the subscription-owned profile ID on refresh.
- Keep a local private key when the server supplies a placeholder; apply endpoint, PSK, and cohort updates.
- Keep profiles from other subscriptions and manual imports separate.
- No breaking wire or Room schema changes.

## Capabilities

### New Capabilities

- `awg-subscription-refresh`: durable membership and safe credential updates.

### Modified Capabilities

- None.

## Impact

- `:core:data` repository persistence and `:app` subscription and bootstrap imports.
- Existing rows without provenance stay unchanged because their source cannot be proved.
