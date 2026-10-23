# Change: Fence subscription ABA and recover every commit boundary

Task ID: `DAT-1792728666702112`

## Why

Content equality and an accepted-refresh counter cannot distinguish an identical group that was deleted and recreated, edited and reverted, or restored from an old snapshot. Delayed valid responses can therefore rotate credentials after ownership changed. Existing recovery tests do not exercise every partial-write boundary.

## What Changes

- Persist an opaque group generation assigned by its storage owner, and reject captured ownership from before any intervening effective mutation.
- Preserve accepted after-images and observer publication across interruption at every AWG and group write boundary.
- BREAKING: replace subscription refreshRevision with group ownershipGeneration. Upgrade persisted groups and pending subscription journals once; callers must capture the stored group.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `subscriptions/atomic-awg-refresh`: persistent ownership, durable migration and complete fault/recovery evidence.

## Impact

- Kotlin runtime-state group storage and core:data mutation/recovery contracts; subscription and migration consumers/tests.
- No wire protocol, server, dependency, native crate, locale or deployment changes.
