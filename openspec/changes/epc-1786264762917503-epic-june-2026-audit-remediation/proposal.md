# Change: Epic - June 2026 full-project audit remediation

Task ID: `EPC-1786264762917503`

## Why

Remediate the findings from the 2026-06-10 full-project audit (six parallel specialized passes: Rust API quality, unsafe code, async cancel-safety, JNI boundary, Kotlin/Android design, and architecture layering) across the ~112-crate native Rust workspace and the Android app. Close the one real shutdown bug, the one privacy-rule violation, and the cluster of medium-severity correctness and structural issues, while preserving the confirmed-healthy posture (no UB, no JNI signature mismatches, no…

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `epic-june-2026-audit-remediation`: Epic - June 2026 full-project audit remediation

### Modified Capabilities

- None.

## Impact

- Portfolio area: `epic`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
