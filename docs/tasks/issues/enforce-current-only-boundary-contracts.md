---
title: Enforce current-only Kotlin-Rust boundary contracts
type: task
status: doing
area: engine
priority: critical
owner: Boundary contract coordinator
parent: null
blocks: []
blocked_by: []
created: 2026-07-10
updated: 2026-07-10
---

## Goal

Remove legacy compatibility from the audited Kotlin-Rust boundary after explicit approval for breaking migrations and high-blast-radius changes. Current producers must emit explicit current schema versions, current consumers must reject missing or non-current versions, and historical persisted settings must not receive semantic migration.

## Scope

- Proxy, tunnel, and relay native-config schema envelopes and version validation.
- Diagnostics engine request, progress, and report schema envelopes.
- Runtime telemetry snapshot schema validation where the native producers already emit an explicit version.
- The historical AppSettings relay xHTTP tag migration, while retaining protobuf reservations and ordinary unknown-field preservation.
- Remembered-policy replay behavior that exists only to support historical payload shapes.
- Contract fixtures, governance tests, architecture documentation, and the boundary audit report.

## Ownership

The coordinator is the only writer. Specialist lanes are read-only: protobuf/DataStore migration, proxy/replay compatibility, and relay/diagnostics/telemetry schema compatibility. Serialized shared files, schema constants, and golden fixtures remain serialized in the coordinator lane.

## Ship definition

- Current Kotlin native-config and diagnostics producers always emit `schemaVersion`.
- Kotlin and Rust consumers reject missing, older, and future schema versions at the boundary.
- Relay native config accepts only schema version 8.
- Historical AppSettings xHTTP tag bytes are not semantically migrated; reserved numbers remain reserved.
- Current serializer omissions that are not legacy behavior remain decodable or are made explicit on the producer before Rust defaults are removed.
- Focused Kotlin/Rust tests, cross-language governance, architecture health, and broader practical gates pass without live-network dependencies.

## Work log

- 2026-07-10: User explicitly superseded the original legacy-support requirement and approved breaking/high-blast-radius contract changes.
