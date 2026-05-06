---
title: Split TCP typed payload adapter by family
type: task
status: backlog
area: transport
priority: medium
owner: unassigned
parent: epic-clear-post-srp-architecture-regressions
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Split TCP typed payload adapter by family #repo/RIPDPI #area/transport #status/backlog 🔼

## Summary

`ripdpi-config/src/model/tcp/payload.rs` now provides typed TCP payload access,
but it became the residual TCP-family catchall. It owns the typed enum, common
payload, invariant validation, and conversion/accessor logic for sequence
overlap, fake, hostfake, TLS random record, IP fragmentation, fake RST, flags,
and IPv6 extension state.

## Audit citation

- `native/rust/crates/ripdpi-config/src/model/tcp/payload.rs` lines 98-167.
- Native hotspot budget: measured `316` production LOC, budget `170`.

## Scope

- In scope: split typed payload conversion/accessors by TCP family, preserve the
  public typed facade, and keep invalid payload-family combinations rejected.
- Out of scope: changing desync strategy semantics or CLI/config grammar unless
  required by the typed payload boundary.

## Acceptance criteria

- [ ] TCP typed payload code is split into focused family modules.
- [ ] `payload.rs` is a small facade/orchestrator and is under the native hotspot
    budget.
- [ ] Existing typed payload and invariant tests still pass; add tests for any
    moved family that lacks direct coverage.
- [ ] `python3 scripts/ci/check_native_hotspot_budgets.py` passes.

## Links

- [[Epic - Clear post-SRP architecture regressions]]
- Follow-up to: [[Replace TcpChainStep field bag with typed variants]]
