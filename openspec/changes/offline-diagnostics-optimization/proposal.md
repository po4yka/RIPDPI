# Change: Tune DNS scoring and compact repeat diagnostics locally

Task ID: `DGN-1794388936359293`

## Why

DNS ranking uses fixed weights. Repeated diagnostics use the first targets in a
catalog when the safety budget is smaller than that catalog. Local observations
can improve both choices without an external service or extra network probes.

## What Changes

- Fit DNS ranking weights with bounded, deterministic Differential Evolution on
  prior local observations. Accept a model only after chronological validation.
- Use Simulated Annealing to select a compact diagnostic target set within the
  existing automatic-probe limits. Retain controls and distinct failure evidence.
- Keep full manual checks, DNS quarantine, wire schemas, and storage compatible.
- Add no dependency on metaheuRUSTics.

## Capabilities

### New Capabilities

- `local-offline-optimization`: bounded local DNS calibration and diagnostic selection.

### Modified Capabilities

- None.

## Impact

- Rust `ripdpi-dns-resolver` health scoring and Kotlin `:core:diagnostics`.
- No new production dependencies, public wire fields, database fields, or UI.
- No breaking changes.
