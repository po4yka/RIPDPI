---
id: DGN-1794740137965522
title: Correct Kotlin DNS diagnostic evidence and HTTPS lookups
kind: bug
status: doing
area: diagnostics
priority: high
owner: Diagnostics repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-dia12-kotlin-dns
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Report accurate, bounded Kotlin DNS and HTTPS readiness measurements for the ten DIA-12 audit findings.

## Acceptance criteria

Verify each requirement in the linked specification with its owning host tests. Exercise the shared behavior with local protocol fixtures and run the combined source gates before fast-forward integration into main. Record platform or external compatibility separately.

## Parallel ownership

- HTTPS writer in RIPDPI-dia12-https owns HTTPS lookup and ECH endpoint selection, Kotlin and native handshake interfaces, ECH screen projections, and their tests.
- HTTP writer in RIPDPI-dia12-httpjson owns the shared HTTP and UDP diagnostic runners, JSON validation, DNS attempt provenance, and their tests.
- Wire writer in RIPDPI-dia12-wire owns the common wire decoder and adapters, EDNS negotiation and evidence, address-family handling, and their tests. HTTP call sites for EDNS are serialized after the HTTP writer.
- Integration writer in RIPDPI-dia12 owns DNS comparisons and downstream presentation, the DoH ID update, locale resources and translation exports, OpenSpec, API snapshots, and combined checks. Shared presentation paths are serialized by probe kind. Dependencies and baselines are outside this repair.
