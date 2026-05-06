---
title: Replace TcpChainStep field bag with typed variants
type: task
status: done
area: transport
priority: medium
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Replace TcpChainStep field bag with typed variants #repo/RIPDPI #area/transport #status/done 🔼

## Summary

`TcpChainStep` still stores mutually exclusive payload families in one struct:
hostfake, fake ordering, flags, sequence overlap, fragmentation, IPv6 forgery,
and random-host state. Replace or wrap it with typed variants so invalid
combinations cannot spread into downstream conversion and validation code.

## Audit citation

- `native/rust/crates/ripdpi-config/src/model/tcp.rs` lines 185-221.

## Scope

- In scope: typed TCP chain-step model, conversion from persisted config,
  validation, and downstream consumers.
- Out of scope: changing serialized field names without a schema migration plan.

## Acceptance criteria

- [x] Mutually exclusive step families are represented by typed variants or an
    equivalent invariant-preserving model.
- [x] Legacy config parsing still accepts existing profiles and reports
    actionable validation errors for invalid combinations.
- [x] Downstream conversion no longer polices payload-family invariants
    repeatedly.
- [x] Golden or round-trip tests cover representative TCP step families.

## Links

- [[Epic - Finish SRP residual architecture debt]]
