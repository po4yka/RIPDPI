## Why

Task ID: `DAT-1792683530998740`

Valid subscription AWG data currently persists before the group ownership check. A response from an obsolete source can therefore rotate a saved row and its secrets while reporting `Superseded`.

## What Changes

- Parse responses without persistence; commit accepted AWG and group after-images under the existing recovery owner.
- Reject stale groups before preparing or writing AWG state.
- Recover interrupted accepted updates from one encrypted journal entry.

## Capabilities

### New Capabilities

- `subscriptions/atomic-awg-refresh`: current-owner subscription rotation and recoverable publication.

### Modified Capabilities

None. The selector-reserve design explicitly excluded AWG multi-store transactions.

## Impact

App subscription orchestration, core-data AWG/recovery storage, runtime-state journal family, and their tests. The internal mutation contract gains a required operation and the encrypted journal gains a subscription intent and stored subscriptions gain an accepted-refresh revision; all implementations are updated together. No wire, JNI, Rust, locale, dependency or deployment change.
