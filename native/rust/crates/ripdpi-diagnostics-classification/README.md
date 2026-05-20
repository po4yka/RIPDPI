# ripdpi-diagnostics-classification

**Role:** classification. **Layer:** L6 — diagnostics / monitor.

## Responsibility

Turns collected probe **observations** into the typed diagnostic **verdict**
(`TRANSPARENT_WORKS`, `OWNED_STACK_ONLY`, `NO_DIRECT_SOLUTION`,
`IP_BLOCK_SUSPECT`). Owns `classification/diagnosis.rs` and the per-domain
diagnosis logic — RIPDPI's authoritative blocking-verdict source.

## Main dependencies

`ripdpi-diagnostics-candidates`, `ripdpi-diagnostics-contracts`,
`ripdpi-failure-classifier`.

## Extension points

A new classification rule, observation type, or diagnosis code.

## What must not be added here

Network I/O or probing — classification is a pure function over evidence
already collected by the probe crates. No scan orchestration.

---
See [`DIAGNOSTICS_ARCHITECTURE.md`](../../../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md)
for the scan pipeline and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§3 for adding a probe.
