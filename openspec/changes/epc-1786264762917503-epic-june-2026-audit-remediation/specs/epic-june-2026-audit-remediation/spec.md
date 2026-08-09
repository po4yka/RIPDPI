## Purpose

Define the observable completion contract for Epic - June 2026 full-project audit remediation. Remediate the findings from the 2026-06-10 full-project audit (six parallel specialized passes: Rust API quality, unsafe code, async cancel-safety, JNI boundary, Kotlin/Android design, and architecture layering) across the ~112-crate native Rust workspace and the Android app. Close the one real shutdown bug, the one privacy-rule violation, and the cluster of medium-severity correctness and structural issues, while preserving the confirmed-healthy posture (no UB, no JNI signature mismatches, no…

## ADDED Requirements

### Requirement: REQ-EPC-1786264762917503-001 — Implement Epic - June 2026 full-project audit remediation and verify its portfo…

The RIPDPI implementation MUST satisfy this portfolio criterion: Implement Epic - June 2026 full-project audit remediation and verify its portfolio acceptance criteria.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Implement Epic - June 2026 full-project audit remediation and verify its portfolio acceptance criteria
