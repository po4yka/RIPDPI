# Change: Localize diagnostic and subscription status text

Task ID: `UIX-1790340279870792`

## Why

DNS integrity and domain reachability cards show raw English enum names in every locale. Seven subscription failover screen and settings strings remain English in seven translated locale sets.

## What Changes

- Show localized Idle, Running, Complete, and Failed labels on both diagnostic cards.
- Translate the seven subscription failover strings in Arabic, German, Spanish, Persian, French, Russian, and Simplified Chinese.
- Breaking changes: none.

## Capabilities

### New Capabilities

- `localized-diagnostic-and-subscription-status`: localized status labels and failover copy in the Android UI.

### Modified Capabilities

- None.

## Impact

- Android app Compose UI and app string resources. No persisted, wire, service, or native contract changes.
