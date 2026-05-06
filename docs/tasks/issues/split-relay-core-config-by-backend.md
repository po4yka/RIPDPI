---
title: Split relay core config by backend
type: task
status: done
area: relay
priority: medium
owner: unassigned
parent: epic-clear-post-srp-architecture-regressions
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Split relay core config by backend #repo/RIPDPI #area/relay #status/done 🔼

## Summary

`ripdpi-relay-core/src/config.rs` centralizes common relay config, backend
variant contracts, flattened serialization/deserialization, backend kind
capability checks, finalmask config, chain relay, MASQUE, ShadowTLS, NaiveProxy,
Cloudflare tunnel, VLESS, TUIC, and Hysteria2 backend fields. Backend config
changes still land in one shared relay contract module.

## Audit citation

- `native/rust/crates/ripdpi-relay-core/src/config.rs` lines 39-240 and
  conversion implementations later in the same file.
- Full native LOC scan: about `580` non-comment production lines.

## Scope

- In scope: per-backend config modules, flattened wire compatibility,
  common/finalmask config extraction, and conversion tests.
- Out of scope: changing relay runtime behavior or serialized field names.

## Acceptance criteria

- [x] Relay backend DTOs and conversions are split by backend family.
- [x] `config.rs` remains a small facade preserving the serialized JSON
    contract.
- [x] Golden or round-trip tests prove flattened relay config compatibility.
- [x] No new broad-root or oversized native hotspot indicators are introduced.

## Links

- [[Epic - Clear post-SRP architecture regressions]]
