# Change: Triage undocumented orphan crates and document NATIVE_RUST.md prune candidates

Task ID: `RST-1786264762917099`

## Why

The 2026-06-10 architecture audit flagged diagnostics prune candidates. Re-verified 2026-06-11 against docs/architecture/NATIVERUST.md and the workspace Cargo.tomls — the earlier "undocumented orphan" framing was inaccurate and is corrected here:

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `triage-undocumented-orphan-diagnostics-crates`: Triage undocumented orphan crates and document NATIVE_RUST.md prune candidates

### Modified Capabilities

- None.

## Impact

- Portfolio area: `rust-native`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
