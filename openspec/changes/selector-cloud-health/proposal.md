# Prefer selector probe evidence over cloud down health

Task ID: `OUT-1795326656806182`

## Why

Control infrastructure can report an endpoint down while a device can still use it. Cloud status can hide a working current member or an eligible reserve from local selection.

## What Changes

- Measure the current automatic member when its cloud status is down.
- After local transfer failures, admit one down reserve under the existing selector rules and probe budget.

## Capabilities

### New Capabilities

- `selector-cloud-health` — Treat cloud down health as advisory during selector recovery.

### Modified Capabilities

- None.

## Impact

Android selector probe planning and transient failover evidence.
