---
id: OUT-1792820412611017
title: Implement Hysteria2 remote port hopping
kind: feature
status: done
area: outbound
priority: high
owner: Transport implementation
parent: null
blocked_by: []
spec_mode: required
openspec_change: hysteria-remote-port-hopping
created: 2026-10-24
updated: 2026-10-24
closed_at: "2026-10-24T11:19:21Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Source PR 661 merged as e22c8d66d8fa0c9245eb5bf8ef802e24028d0f48; main CI 40085973754 passed. Targeted Kotlin/native tests, full static analysis, eight real hopping peer cases, Linux GRO and four Android ABI builds passed. Artifact 12556472475 passed the canonical six-APK verifier. No physical-device or production acceptance is claimed.
---

## Goal

Keep one authenticated Hysteria2 session usable across scheduled changes of its remote server port and local protected UDP socket, from standard subscription import to native TCP and UDP payload.

## Acceptance criteria

- Standard URI, sing-box, Clash and existing bundle extras produce one validated hopping model; malformed or conflicting inputs fail admission.
- Storage, activation, chain entry and native JSON preserve the complete policy.
- Fixed and random schedules rotate remote and local ports with protected sockets, prior receive drainage and Salamander preservation.
- A pinned independent peer proves TCP and UDP continuity, multiple remote ports, IPv4 and IPv6, and cleanup without reconnect.
- Negative cases cover protection failure, interval/port bounds, conflicting data and shutdown.
- Relevant local and combined-tree gates pass, with hosted and device evidence stated separately.

## Ownership

- Native writer: this portfolio record and OpenSpec change; `native/rust/crates/ripdpi-hysteria2/**`; affected `ripdpi-relay-core` flat/typed config, conversions, direct/chain builders and tests; `test-lab/acceptance/peers/**` hopping scenario additions; `test-lab/acceptance/generate_manifest.py`, its generated `manifest.json`, the acceptance README and the narrow hopping job in `.github/workflows/local-acceptance.yml`.
- Kotlin writer: Hysteria import/model/validation, public relay persistence, selector activation, service resolution, engine API DTO/JSON and affected tests. No native edits.
- Root integrator: producer repository, cross-repository contract, combined-tree checks, shared task-board conflict resolution, final PR and main integration.
- Stored-health-policy migration writer: separate prerequisite; other writers do not change its helper or overwrite its changes.
- Serialized files: native writer owns Rust config contracts; Kotlin writer owns engine API and chain DTO contracts. No Cargo.lock, dependency pin, protobuf, locale or golden changes without root coordination.

## Scope limits

Only local code and controlled real-peer acceptance. No fleet/provider mutation, private credentials, production deployment, physical device or new production dependency. Existing fixed-range producer contract does not need expansion to enable client standard imports.

## Producer dependency

Coordinate qualification with `po4yka/ripdpi-vpn-deploy#OPS-1792820562787120`. The producer keeps the existing range and fixed-interval schema and removes its recipient refusal only after client acceptance.
