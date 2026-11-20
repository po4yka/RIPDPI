---
id: DGN-1795085498592630
title: Send opt-in anonymous aggregated field reports
kind: feature
status: review
area: diagnostics
priority: high
owner: Diagnostics engineer
parent: EPC-1795085162356362
blocked_by: [DGN-1795085357320979, DAT-1795085420746769]
spec_mode: required
openspec_change: dgn-1795085498592630-opt-in-field-report-client
created: 2026-11-19
updated: 2026-11-20
status_detail: Gradle gates passed on 2026-11-20; owner waived local counsel and approved the AGENTS.md wording; device run and remote CI remain
---

## Goal

Opt-in, anonymous, aggregated hourly field reports sent only through the active tunnel. Counterparts: deploy D1, D2, D6, D7.

## Acceptance criteria

- Off by default and revocable; nothing sent without consent.
- Reports validate against client-field-report v1; buckets under 5 attempts are suppressed.
- Daily cap respected; failure drops the report.
- client-legal-safety and legal-check reviews recorded.
