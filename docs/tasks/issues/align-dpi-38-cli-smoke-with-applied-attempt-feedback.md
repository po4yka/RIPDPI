---
id: CIC-1794764263401395
title: Align DPI-38 CLI smoke with applied-attempt feedback
kind: bug
status: doing
area: ci
priority: high
owner: DPI-38 CLI smoke maintainer
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-15
updated: 2026-11-15
spec_reason: test-only
---

## Goal

Make the adaptive CLI capture scenario test an applied HTTP strategy. Preserve the DPI-38 rule that plain fallback earns no evolution credit and keep CLI host-autolearn persistence coverage.

## Acceptance criteria

- Match the protocol filter to the fixture traffic and check actual captured request bytes.
- Add a plain-fallback negative control without external warmup probes.
- Preserve the host-autolearn artifact and report Linux capture and external warmup checks that cannot run locally.
- Run the affected CLI tests, strict Clippy, independent review, and `just ci-gates`.

## Ownership

- The smoke writer owns `native/rust/crates/ripdpi-cli/tests/packet_smoke.rs` and private files below `tests/packet_smoke/` in a separate worktree.
- The integrator owns this task, its execution file, the generated board, report, and integration.
- No dependency, baseline, golden, production runtime, schema, or registry changes are planned.

## Evidence

The published source `44c5b4bcbddb8d7fb61313e37395c9cf380c2afd` failed `cli_packet_smoke_adaptive_family` in GitHub run 43734875574. The driver sends HTTP, but `-K t` selects TLS; both local flows use plain fallback. The new applied-attempt feedback gate correctly rejects credit. Local reproduction must not run the five external warmup probes without explicit permission.
