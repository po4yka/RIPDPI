---
id: DGN-1790436692670903
title: Redact legacy network snapshot history during database upgrade
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics data migration
parent: null
blocked_by: []
spec_mode: required
openspec_change: redact-legacy-network-snapshots
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Existing diagnostic network snapshot history is made storage-safe when a version 13 database upgrades to version 14. Valid rows retain timestamps, session links, and useful coarse network evidence. Only unreadable or structurally unsafe snapshot rows are removed.

## Acceptance criteria

- The 13→14 Room migration replaces known raw Wi-Fi, cellular, DNS, local address, and private DNS fields using the capture-time redaction contract.
- Upgrade keeps valid snapshot rows and other diagnostics history; a malformed snapshot payload fails closed for its own row.
- A production-builder upgrade regression covers normal and malformed rows, plus batched processing.
- The generated version 14 schema, diagnostics-data and related diagnostics tests, lint, task validation, and architecture check pass.

## Parallel ownership

This task owns `core/diagnostics-data` database version, migration, generated Room schema, migration tests, and this task/OpenSpec. Other agents own `core/diagnostics`, app UI, and service paths. Public IP and ASN remain subject to a separate product decision and are outside this migration.
