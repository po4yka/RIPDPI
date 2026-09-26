---
id: DGN-1790433240059940
title: Exclude raw network identities from diagnostic snapshots
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics network privacy agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: redact-network-snapshot-identities
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Newly captured diagnostic network snapshots keep useful path summaries without raw Wi-Fi identities, cellular operator identities, DNS addresses, local addresses, or private DNS hostnames. Existing rows require a separate data migration.

## Acceptance criteria

- `AndroidNetworkMetadataProvider` returns a storage-safe snapshot for all production snapshot write paths.
- A serialization regression test excludes raw network identifiers and addresses while retaining count and coarse status.
- Existing summary and archive projections remain meaningful.
- Focused diagnostics unit tests, detekt, OpenSpec, and task contracts pass.

## Parallel ownership

This task owns `NetworkMetadataProvider.kt`, new snapshot privacy tests, and its task/OpenSpec artifacts. Other agents own scan coordinator and export code. The public-IP resolver and runtime public-IP fields are outside this atomic change pending product direction.

## Residual

Existing snapshot rows may contain raw identities indefinitely when history retention is disabled. They need a data migration. `publicIp` and `publicAsn` remain raw pending the external resolver decision.
