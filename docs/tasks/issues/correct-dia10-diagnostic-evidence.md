---
id: DGN-1794739971189458
title: Correct DIA-10 diagnostic evidence
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-dia10-diagnostic-evidence
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Make each DIA-10 result describe the evidence the probe collected.

## Acceptance criteria

- Fix all nine audit findings with regression coverage.
- Preserve neutral throughput controls, separate transport checks, bounded NAT64 fallback, and selective matrix coverage.
- Run focused local tests and source gates on the combined tree.

## Ownership

- Transfer lane owns the Guided Tools throughput entry and transfer measurement, progress, and report tests.
- PT lane owns the independent transport checks and their UI and tests.
- Endpoint lane owns Rust service, circumvention, and IP family probes and tests.
- Integration lane owns selective matrix evidence, OpenSpec, and integration checks.
- Integrate locale resources in series. The PT lane owns PT-specific strings. The transfer lane owns throughput-specific strings. No parallel writer owns the generated locale manifest.
