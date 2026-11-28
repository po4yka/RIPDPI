---
id: DGN-1795085498592630
title: Send opt-in anonymous aggregated field reports
kind: feature
status: review
area: diagnostics
priority: critical
owner: Diagnostics engineer
parent: EPC-1795085162356362
blocked_by: [DGN-1795085357320979, DAT-1795085420746769]
spec_mode: required
openspec_change: dgn-1795085498592630-opt-in-field-report-client
created: 2026-11-19
updated: 2026-11-26
status_detail: Gradle gates passed on 2026-11-20; owner waived local counsel and approved the AGENTS.md wording; remote CI passed (run 46009834608 on d9aa00b99f); a device run of the consent flow (off by default, opt in, one hourly report through the tunnel, revoke) and the live ingest (deploy D2 and D7) remain After this task closes, run ./taskctl close purge DAT-1795085420746769; its done record stays until this incoming reference goes.
---

## Goal

Opt-in, anonymous, aggregated hourly field reports sent only through the active tunnel. Server counterparts: `po4yka/ripdpi-vpn-deploy#SEC-1795086333272606` (ingest) under epic `po4yka/ripdpi-vpn-deploy#EPC-1795085241910080`.

## Acceptance criteria

- Off by default and revocable; nothing sent without consent.
- Reports validate against client-field-report v1; buckets under 5 attempts are suppressed.
- Daily cap respected; failure drops the report.
- client-legal-safety and legal-check reviews recorded.

## Priority rationale

Raised from high to critical on 2026-11-26. Nearly all external blocking evidence available to the project is single-vantage: one author, one network, one date, with conclusions that readers inferred. Aggregated opt-in reports keyed by client ASN and server ASN, with no addresses, are the only source that can close that gap. The same reports are the input that the paired server's block-detection alert needs, so a delay here also delays that alert. The scope and the privacy limits above do not change.
