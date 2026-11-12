---
id: RLY-1794491312461911
title: Repair Snowflake source and bridge configuration contracts
kind: bug
status: doing
area: relay
priority: high
owner: Snowflake repair coordinator
parent: null
blocked_by: []
spec_mode: required
openspec_change: tun08-snowflake-repairs
created: 2026-11-12
updated: 2026-11-12
---

## Goal

Describe the observable outcome.

## Acceptance criteria

Define verifiable completion criteria.

## Ownership

- Source worker: native/pluggable-transports/sources.json, source-specific build tests and Snowflake ADR. One serialized owner for the PT manifest; no Cargo/Gradle dependency catalogs.
- Validation worker: TorBridgeValidation.kt and its settings, service and app validation tests.
- Coordinator: task/OpenSpec artifacts, combined gates, evidence, commits and main integration.
- Review agents: read-only; no source changes. Each writer uses a separate worktree.

## Scope

TUN-08 F1 and managed negotiation/endpoint/stop gaps already have fixes on main. Preserve them and revalidate; do not create duplicate changes. Fix F2 with an official source that implements DTLS mimic and F3 with early rendezvous validation. Keep Tor-only activation, saved profile data and VPN rejection. External network/device tests are not authorized.
