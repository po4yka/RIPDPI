---
id: RLY-1793949568813382
title: Replace the Shadowsocks protocol core with upstream
kind: feature
status: doing
area: relay
priority: high
owner: Primary implementer
parent: null
blocked_by: []
spec_mode: required
openspec_change: replace-shadowsocks-core
created: 2026-11-06
updated: 2026-11-06
---

## Goal

Use the upstream Shadowsocks implementation for authenticated TCP and UDP relay traffic. Remove the private protocol codec without losing Android socket protection, replay checks, calibrated time, stream shutdown, or chained transport support.

## Acceptance criteria

- The six supported AEAD methods use upstream protocol code. Unsupported methods and invalid keys fail before network access.
- TCP, UDP, server-first traffic, half-close, cancellation, replay rejection, and authenticated time retain regression coverage.
- SS2022 identity-key profiles use the upstream EIH implementation where the protocol supports it.
- Existing profile and JNI contracts remain aligned with native capabilities.
- Targeted tests, dependency checks, source gates, and independent review pass before integration.
- Commit the result, integrate into main, and push. Report hosted CI and Android validation separately.

## Ownership

- Primary implementer: native adapters, dependency manifests and locks, Kotlin capability alignment, fixtures, and integration.
- Vendor writer: isolated `/tmp/ripdpi-shadowsocks-vendor`, vendor subtree only.
- Fixture and validation writer: isolated `/tmp/ripdpi-shadowsocks-fixture`, local fixture and Kotlin Shadowsocks admission only.
- Read-only delegates: upstream API analysis and independent security/cancellation review.
- Serialized files: the primary implementer alone changes Cargo.lock, workspace dependencies, and contract inventories.
