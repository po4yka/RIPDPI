# Change: Recover renewed subscriptions through manual refresh

Task ID: `DAT-1791480001436232`

## Why

Cached expiry or a terminal HTTP result blocks all later refreshes. A server can renew the same URL, but the user cannot fetch its new profiles.

## What Changes

- Let explicit manual refresh recheck long-lived subscriptions with cached terminal state or expiry.
- Keep automatic retry suppression and single-use bootstrap protection.
- Require valid current server content before clearing the old failure.

## Capabilities

### New Capabilities

- `manual-subscription-recovery`: Explicit renewal checks preserve automatic retry and expiry safety.

### Modified Capabilities

- None.

## Impact

Only app refresh coordination and tests. No storage schema or network protocol change.
