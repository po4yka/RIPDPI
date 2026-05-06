---
title: Decouple monitor engine from concrete diagnostics lanes
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: epic-clear-post-srp-architecture-regressions
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Decouple monitor engine from concrete diagnostics lanes #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Summary

`ripdpi-monitor-engine` still links concrete diagnostics lane crates directly:
candidates, classification, DNS, HTTP, runner, Telegram, TLS, transport, failure
classification, proxy config, runtime platform, and telemetry. The architecture
gate still records this crate as a dependency hub.

## Audit citation

- `native/rust/crates/ripdpi-monitor-engine/Cargo.toml` lines 13-27.
- Architecture-health indicator: `dependency-hub`, internal dependency count
  `16`, limit `12`, plus discouraged concrete-lane edges.

## Scope

- In scope: diagnostics runner/lane contracts, adapter crates or registries,
  monitor-engine imports, and Cargo dependency cleanup.
- Out of scope: changing probe behavior, scan wire contracts, or diagnostics
  report semantics.

## Acceptance criteria

- [ ] `ripdpi-monitor-engine` depends on lane contracts/adapters rather than
    each concrete diagnostics implementation crate.
- [ ] Internal dependency count drops below the architecture-health limit or the
    remaining edges are intentionally narrow and documented.
- [ ] Existing monitor-engine tests and native architecture contracts pass.
- [ ] `python3 scripts/ci/check_architecture_health.py --check` has no new or
    worsened entries.

## Links

- [[Epic - Clear post-SRP architecture regressions]]
