# Require current selector and direct path evidence

Task ID: `DGN-1795329243655314`

## Why

A selector probe can finish on a different network from the one where it started. The local policy learner can also receive duplicate or delayed failure signals.

## What Changes

- Bind an opted-in field-report probe to the network generation and the allowed report label at its start.
- Require two distinct, fresh failures on the same path before the learner writes or extends a negative direct-path policy.

## Capabilities

### New Capabilities

- `probe-evidence-freshness` — Use current network evidence for aggregates and local learning.

### Modified Capabilities

- None.

## Impact

Selector field-report admission and direct-path policy learning.
