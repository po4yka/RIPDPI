---
title: Tighten runtime decision ports exports
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Tighten runtime decision ports exports #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Summary

Restrict `native/rust/crates/ripdpi-runtime-decision-ports/src/lib.rs` to narrow port traits and selected DTOs so it no longer leaks broad adaptive and runtime-policy module surfaces.

## Context

The crate is intended to decouple proxy execution from policy engines, but its nested re-export modules still expose large parts of adaptive policy, direct-path learning, and runtime policy internals.

## Acceptance criteria

- [ ] Remove broad module re-exports that let callers bypass selected-decision ports.
- [ ] Re-export only stable port traits, input/output DTOs, and error types needed by runtime execution.
- [ ] Migrate internal callers to the narrow API.
- [ ] Add an architecture contract or grep guard against broad policy-engine re-exports.
- [ ] Keep the native workspace compiling without reintroducing proxy-runtime policy-engine edges.

## Completion outcome

Closing this task means `ripdpi-runtime-decision-ports` is a true port crate: it exposes only the traits, DTOs, and errors required by runtime execution, while adaptive/runtime-policy engine internals remain behind their owning crates.

## Regression guardrails

- Do not `pub use` broad adaptive, direct-path-learning, or runtime-policy modules from the port crate.
- Do not let proxy-runtime regain direct policy-engine dependencies through a port-crate shortcut.
- Do not export convenience helpers that perform policy selection inside the execution-facing port.
- Do not close the task without an architecture guard preventing broad re-export regressions.
- Do not close the task without unit or contract tests proving callers use the narrowed ports instead of policy-engine internals.

## Links

- [[Epic - Post-refactor architecture cleanup]]
