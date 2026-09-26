# Change: Correct diagnostics Rust audit defects

Task ID: `DGN-1790419498655006`

## Why

Diagnostics can report success for incomplete responses, classify a successful HTTPS baseline as failed, lose an unread scan report, and continue I/O after its deadline. These findings were traced from the 14 requested Rust crates to their callers and confirmed against the current implementation.

## What Changes

- Report only completed HTTP and Telegram responses as successful probe evidence.
- Preserve completed session reports and bound live DNS lookup workers.
- Honor scan deadlines across address attempts and proxy startup.
- Record all capabilities required by the current candidate and classify only failed baseline observations as failures.
- Keep dormant probe parsers and byte limits correct for future callers.

## Capabilities

### New Capabilities

- `diagnostics/rust-probe-integrity`: Define successful probe evidence, scan lifetime, and candidate classification behavior.

### Modified Capabilities

- None.

## Impact

- Rust crates: `ripdpi-diagnostics-contracts`, `-http`, `-telegram`, `-probes`, `-transport`, `-candidates`, `-classification`, and `ripdpi-monitor-engine`.
- `core/engine` JNI binding documentation follows the new report-reuse error; no wire schema, Android resource, dependency, or external service change is planned.
