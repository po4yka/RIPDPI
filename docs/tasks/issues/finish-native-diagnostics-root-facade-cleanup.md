---
title: Finish native diagnostics root facade cleanup
type: task
status: done
area: diagnostics
priority: medium
owner: unassigned
parent: epic-clear-post-srp-architecture-regressions
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Finish native diagnostics root facade cleanup #repo/RIPDPI #area/diagnostics #status/done 🔼

## Summary

The native architecture gate still reports broad root facades in the diagnostics
compatibility layer. `ripdpi-diagnostics-net`, `ripdpi-diagnostics-protocols`,
and `ripdpi-diagnostics-runner` expose or aggregate multiple protocol families
from crate roots, which keeps broad diagnostics imports available after the
crate split.

## Audit citation

- `native/rust/crates/ripdpi-diagnostics-net/src/lib.rs` lines 9-72,
  `rootExports=29`, limit `10`.
- `native/rust/crates/ripdpi-diagnostics-protocols/src/lib.rs` lines 1-23,
  `rootExports=12`, limit `10`.
- `native/rust/crates/ripdpi-diagnostics-runner/src/lib.rs` lines 1-41,
  `rootExports=14`, limit `10`.

## Scope

- In scope: compatibility namespaces, root exports, consumer imports, and
  architecture-health rules/baseline cleanup for these diagnostics crates.
- Out of scope: changing diagnostics probe behavior or wire schema.

## Acceptance criteria

- [x] Diagnostics root crates expose only their owned public API.
- [x] Compatibility re-exports are explicit, opt-in/deprecated, or moved to
    dedicated compatibility modules that do not trigger broad-root facade rules.
- [x] Internal consumers import split protocol crates directly.
- [x] `python3 scripts/ci/check_architecture_health.py --check` has no current
    broad-root indicators for these three files.

## Links

- [[Epic - Clear post-SRP architecture regressions]]
