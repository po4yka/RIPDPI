---
title: Split proxy runtime adapter model by operation family
type: task
status: backlog
area: rust-native
priority: high
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Split proxy runtime adapter model by operation family #repo/RIPDPI #area/rust-native #status/backlog ⏫

## Summary

Break up `native/rust/crates/ripdpi-proxy-runtime-adapter/src/model.rs`, which is now the residual native integration hub after the proxy-runtime refactor.

## Context

The re-audit found `ripdpi-proxy-runtime-adapter` directly linked to 13 internal crates, with `model.rs` defining broad inline modules for config projection, desync, decision ports, proxy config, runtime API, services, session, TCP rotation, and protocol auth. This moved coupling out of `ripdpi-proxy-runtime` but did not narrow the adapter boundary.

## Acceptance criteria

- [ ] Replace the inline `model.rs` module bundle with real module files such as `config_projection`, `desync_projection`, `decision_ports`, `proxy_context`, `runtime_api`, `services`, `session`, and `protocol_auth`.
- [ ] Keep public re-exports minimal and operation-family scoped.
- [ ] Avoid adding new dependencies to `ripdpi-proxy-runtime-adapter`.
- [ ] Add or preserve targeted tests around config projection, protocol auth, session parsing, and service-handle construction.
- [ ] `python3 scripts/ci/check_native_hotspot_budgets.py` stays green.

## Completion outcome

Closing this task means `ripdpi-proxy-runtime-adapter` is a set of operation-family adapters, not a single integration model file. Config projection, desync projection, decision ports, runtime API, services, session, and protocol auth should each have a clear owner and a small public surface.

## Regression guardrails

- Do not create another `model`, `types`, `bridge`, or `facade` module that re-exports all adapter families.
- Do not add new direct dependencies to the adapter crate unless they are required by a single focused adapter module.
- Do not move inline modules into files while preserving one broad public import path that internal callers can use as a dependency shortcut.
- Do not close the task if hotspot or architecture checks still identify the adapter as a broad dependency hub.
- Do not close the task without focused unit tests for each extracted adapter family, or a written explanation of why an extracted slice is compile-time/static-analysis only.

## Links

- [[Epic - Post-refactor architecture cleanup]]
