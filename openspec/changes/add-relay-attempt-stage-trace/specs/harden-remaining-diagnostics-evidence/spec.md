## Purpose

Extend diagnostic evidence hardening so runtime relay failures retain their
correlated protocol-stage provenance instead of collapsing to one final
free-form error.

## MODIFIED Requirements

### Requirement: REQ-DGN-1786264762917145-001 — Implement Harden remaining diagnostics evidence and verify its portfolio acceptance criteria

The RIPDPI implementation MUST satisfy the portfolio acceptance criteria for
remaining diagnostics evidence and MUST preserve available, privacy-safe,
correlated relay attempt stage evidence in diagnostic archives without
claiming a cause that the observed stages do not establish.

#### Scenario: A runtime relay failure has stage evidence

- **WHEN** the linked change exports a diagnostic archive for a connection session containing relay attempt stage events
- **THEN** the archive preserves the ordered structured evidence, marks unavailable fields explicitly, and does not replace it with an unsupported causal verdict
