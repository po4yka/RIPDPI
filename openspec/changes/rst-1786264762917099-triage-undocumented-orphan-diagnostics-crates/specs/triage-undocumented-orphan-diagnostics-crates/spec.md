## Purpose

Define the observable completion contract for Triage undocumented orphan crates and document NATIVE_RUST.md prune candidates. The 2026-06-10 architecture audit flagged diagnostics prune candidates. Re-verified 2026-06-11 against docs/architecture/NATIVERUST.md and the workspace Cargo.tomls — the earlier "undocumented orphan" framing was inaccurate and is corrected here:

## ADDED Requirements

### Requirement: REQ-RST-1786264762917099-001 — PR states a verdict for each of the two new orphans and the five prune candidat…

The RIPDPI implementation MUST satisfy this portfolio criterion: PR states a verdict for each of the two new orphans and the five prune candidates.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that PR states a verdict for each of the two new orphans and the five prune candidates

### Requirement: REQ-RST-1786264762917099-002 — NATIVERUST.md lists every workspace crate (no undocumented crate remains) or th…

The RIPDPI implementation MUST satisfy this portfolio criterion: NATIVERUST.md lists every workspace crate (no undocumented crate remains) or the orphan is deleted.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that NATIVERUST.md lists every workspace crate (no undocumented crate remains) or the orphan is deleted

### Requirement: REQ-RST-1786264762917099-003 — prune-candidates / planned-crates metadata lists exist where crates are kept

The RIPDPI implementation MUST satisfy this portfolio criterion: prune-candidates / planned-crates metadata lists exist where crates are kept.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that prune-candidates / planned-crates metadata lists exist where crates are kept

### Requirement: REQ-RST-1786264762917099-004 — CI guard prevents new direct deps on prune-candidate crates

The RIPDPI implementation MUST satisfy this portfolio criterion: CI guard prevents new direct deps on prune-candidate crates.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that CI guard prevents new direct deps on prune-candidate crates

### Requirement: REQ-RST-1786264762917099-005 — cargo metadata + cargo deny check clean after any deletions; Cargo.lock change…

The RIPDPI implementation MUST satisfy this portfolio criterion: cargo metadata + cargo deny check clean after any deletions; Cargo.lock change is its own reviewed hunk.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that cargo metadata + cargo deny check clean after any deletions; Cargo.lock change is its own reviewed hunk
