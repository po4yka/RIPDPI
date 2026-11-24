---
id: DGN-1794734095979878
title: Repair DIA-09 TCP fat-header diagnostics
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics
parent: null
blocked_by: []
spec_mode: required
openspec_change: dgn-1794734095979878-repair-dia09-fat-header
created: 2026-11-15
updated: 2026-11-24
---

## Goal

Make the native TCP fat-header probe report what it measured for an eligible HTTP target.

## Acceptance criteria

- Catalog profiles select the proper HTTP mode. Admission bounds the actual work.
- HEAD framing, transport errors, cancellation and retry preserve measured evidence.
- Report coverage, candidate comparisons and the connectivity summary do not promote an inconclusive result.
- Targeted regressions and source gates pass on the combined tree.

## Ownership

- Catalog and admission lane owns build-logic catalog targets and monitor request validation. Owns request and SNI limits in the contracts constants file.
- HTTP wire lane owns diagnostics HTTP parsing, authority validation in contracts utilities, and Host handling in the fat-header request file. Owns the shared contracts utility exports and API snapshot.
- Report lane owns classification observation, engine report summary, and Kotlin contextualization. Owns the partial connectivity report fixture if its change is authorized.
- Integration lane owns fat-header writing, sequence and classification, cancel propagation, runner attempts, and coverage. Owns task, OpenSpec, and combined validation.
- Catalog and the partial connectivity report fixture have separate writers. Review combined changes before integration.
