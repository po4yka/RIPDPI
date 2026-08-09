# Change: Implement typed connection-freeze phases and guarded retries

Task ID: `DGN-1786299627046211`

## Why

The classifier currently collapses handshake-stage silence and post-data stalls
into one failure class and stores the distinguishing observation in string tags.
Retry selection can then diversify immediately, losing evidence precision and
potentially extending a transient failure.

## What Changes

- Add typed freeze phase and bounded evidence that survive diagnostics serialization.
- Preserve the existing coarse block-signal matrix while carrying refinement alongside it.
- Add a disabled-by-default, observation-driven retry guard that suppresses
  same-destination retries and diversification after confirmed freezes.

## Capabilities

### New Capabilities

- `typed-freeze-evidence`: Classify and expose distinct connection-freeze phases.
- `freeze-retry-guard`: Hold unsafe retry decisions after confirmed freeze evidence.

### Modified Capabilities

- `failure-classification`: Preserve typed refinement beyond the classifier boundary.
- `runtime-policy`: Consume confirmed freeze evidence without changing unset behavior.

## Impact

- Affects Rust classifier and runtime policy, diagnostics contracts, Kotlin
  projections, configuration, exports, and regression tests.
