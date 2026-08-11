---
id: DGN-1786472726885990
title: Unify tls_version_split diagnostic semantics
kind: bug
status: done
area: diagnostics
priority: high
risk: standard
owner: codex
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-08-11
updated: 2026-08-11
spec_reason: regression-tested-single-module
related_tasks: []
status_detail: All acceptance criteria and focused regression gates pass
closed_at: "2026-08-11T18:44:41Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: RED/green regression tests for classification, monitor scoring, and VPN verification; cargo test/clippy/fmt; core diagnostics tests; staticAnalysis; architecture health; cargo metadata all pass
---

## Goal

Represent `tls_version_split` consistently as partial TLS reachability rather than an unconditional strategy success.

## Acceptance criteria

- Strategy observations expose `tls_version_split` as `Partial`, matching the canonical `Attention` outcome bucket.
- Strategy scoring retains the lower split quality signal for candidate ordering but does not count the target as a successful probe.
- VPN verification does not treat `tls_version_split` alone as confirmed access.
- `tls_ok` remains a full strategy success.
- Focused native and Kotlin tests plus repository static analysis pass.
