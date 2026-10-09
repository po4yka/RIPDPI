# Change: Record transfer progress and stopping points

Task ID: `DGN-1791521873290293`

## Why

A median speed hides when an HTTP transfer first produces data and where it stops. Users need measured progress and a precise completion reason without an unsupported provider-cause claim.

## What Changes

- Display bounded live body-byte progress during existing throughput checks.
- Retain per-attempt timelines, expected body length when known, first/last progress times and stop reasons in history and exports.
- Distinguish full HTTP responses from the configured measurement window and from interruptions.
- No breaking changes; old reports and absent progress remain supported.

## Capabilities

### New Capabilities

- `transfer-progress`: live and durable transfer progress evidence.

### Modified Capabilities

- None.

## Impact

- Kotlin core/diagnostics and app; native diagnostics contracts, runner and monitor engine.
- Additive optional progress wire data and versioned generic probe details. No database migration, dependencies, backend or new targets.
