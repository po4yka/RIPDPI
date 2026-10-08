---
id: DGN-1791481942929169
title: Preserve TLS probe failure stages and retry evidence
kind: bug
status: review
area: diagnostics
priority: medium
owner: TLS evidence repair
parent: null
blocked_by: []
spec_mode: required
openspec_change: preserve-tls-probe-evidence
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Preserve the measured TLS failure stage and publish consistent evidence after a successful retry.

## Acceptance criteria

- Pre-handshake failures do not produce ClientHello diagnoses.
- Successful retry reports carry current success evidence and no stale aggregate failure.
- Targeted Rust tests pass.

## Ownership

TLS evidence repair owns runner connectivity/probes/domain.rs, classification/diagnosis/domain.rs, observations/domain.rs, and focused tests. HTTP and DNS classifiers belong to another worker. No lockfile, schema, golden, or dependency edits. Integration owner regenerates the shared board on the combined tree.
