---
id: UIX-1790434646817990
title: Explain redacted network history fields in diagnostics UI
kind: bug
status: review
area: ui
priority: high
owner: Diagnostics network privacy agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: explain-redacted-network-history-fields
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Diagnostics history explains which new network fields were redacted before storage and shows useful counts and coarse values. Hidden mode conceals legacy carrier/operator identifiers, Wi-Fi network IDs, private DNS hostnames, and ASN.

## Acceptance criteria

- New snapshot placeholders show a localized not-stored marker and address counts.
- Session history shows legacy raw values only when sensitive details are explicitly shown; unknown values stay unknown.
- Connection history, which has no sensitive-details toggle, shows address counts and coarse private DNS mode without raw DNS/public IP values.
- Mapper tests, app unit tests, locale lint parity, task/OpenSpec validation pass.

## Parallel ownership

This task owns `DiagnosticsUiNetworkSnapshotMapper.kt`, `HistoryConnectionDetailUiFactory.kt`, their tests, one new string key in all ten `diagnostics_fallbacks.xml` locales, and this task/OpenSpec. Other diagnostics and database changes are separate. The locale lane is reserved for this task.
