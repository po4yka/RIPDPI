---
id: RST-1794723522152914
title: Repair DPI-34 UDP socket and raw packet contracts
kind: bug
status: review
area: rust-native
priority: high
owner: Root
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-dpi34-udp-contracts
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Repair six DPI-34 findings without changing unrelated behavior.

## Acceptance criteria

Each finding has a separate commit, regression or contract evidence, independent review and required gates. Integrate into main and push.

## Parallel ownership

- socket_owner: exclusive upstream UDP socket bind and replacement tuple checks; platform.rs, UDP migration.rs, related socket tests. F1 and the F6 RFC comment in separate commits.
- rebind_owner: QUIC-context activation gate; UDP session.rs, upstream_pump.rs, config/udp.rs and UDP source-rebind tests. F2 only. Do not edit migration.rs.
- raw_owner: ripdpi-strategy-udp, strategy-config and strategy-lua udplen validation, tunnel-intercept udplen materialization/tests. F3 then F4 in separate commits. No runtime wire.rs or schemas.
- Root: OpenSpec/task metadata, docs/native/proxy-engine.md (F5), shared API snapshots if required, integration, combined validation and push. Root serializes docs/packet-strategy-runtime.md after workers finish.
- No worker owns Cargo.lock, dependency versions, locales, goldens or baselines; do not change them.
