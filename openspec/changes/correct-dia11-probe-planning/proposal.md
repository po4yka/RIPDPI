# Change: Correct diagnostic probe budgets and strategy evidence

Task ID: `DGN-1794743403723317`

## Why

The strategy probe can exceed its budget, lose completed evidence, and give a transport or concurrency recommendation from insufficient controls.

## What Changes

- Stop work at its allocated deadline and record the reason for skipped work.
- Separate validated probe responses from transport and concurrency conclusions.
- Keep usable partial evidence and qualify recommendations by measured coverage.
- Keep raw-path measurements separate from the owned in-path observation.
- Validate explicit probe tasks and account for candidate budgets in the execution plan.

## Capabilities

### New Capabilities

- `diagnostics/strategy-probe-planning`: Budget, route, and evidence requirements for strategy probes.

### Modified Capabilities

None.

## Impact

The Rust diagnostics engine, transport and classifier, and Kotlin diagnostics finalization use the same completion and evidence rules. The diagnostic transport continues to measure the QUIC first response. No new network mode is added.
