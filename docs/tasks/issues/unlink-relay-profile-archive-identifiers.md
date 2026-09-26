---
id: DGN-1790430871310010
title: Remove stable relay profile identifiers from diagnostics archives
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics export agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: unlink-relay-profile-archive-identifiers
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Diagnostics archives must not disclose stable relay profile identifiers or identifiers derived from them. Records for the same profile may correlate within one archive only.

## Acceptance criteria

- The relay health JSONL entry uses archive-local aliases for profile and attempt identifiers.
- Native event JSON does not contain a stable relay profile token, attempt ID, or derived event ID.
- A regression test confirms that raw identifiers are absent while same-profile records correlate within one archive.
- Focused `:core:diagnostics` unit tests and task contracts pass.

## Parallel ownership

This task owns only `DiagnosticsArchiveRedactor.kt`, `DiagnosticsArchiveCsvEntryBuilder.kt`, and `DiagnosticsArchiveRelayTraceExporterTest.kt` in `:core:diagnostics`, plus its portfolio and OpenSpec artifacts. Other agents own all remaining diagnostics paths. No serialized shared files are changed.
