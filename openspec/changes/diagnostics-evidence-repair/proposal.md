# Change: Preserve diagnostics evidence and validated recommendations

Task ID: `DGN-1793896868711593`

## Why

Diagnostics can discard a measured TCP strategy, confirm access from a partial report, and turn unavailable probes into clean or interference conclusions. Some transfer targets and TCP heuristics also support stronger claims than their measurements establish.

## What Changes

- Compose and validate the configuration that the report recommends.
- Preserve separate transport-lane results and partial service availability.
- Require complete, current evidence before confirming access.
- Keep unavailable, inconclusive, and successful outcomes distinct throughout summaries.
- Correct alternative-SNI, throughput, and TCP-reset evidence interpretation.

## Capabilities

### New Capabilities

- `diagnostics/evidence-consistency`: Measured outcomes retain their scope and meaning through recommendation, comparison, and presentation.

### Modified Capabilities

- None. Existing diagnostics behavior is constrained by the new evidence requirements.

## Impact

- Affects `:core:diagnostics`, relevant app consumers, diagnostics catalog generation, and Rust diagnostics candidate, runner, classification, and monitor crates.
- BREAKING: recommendation winner IDs and labels become nullable per lane, with a new diagnostics engine schema version. All local consumers change together; no compatibility shim is added.
- No production dependency, external service, or deployment is added.
