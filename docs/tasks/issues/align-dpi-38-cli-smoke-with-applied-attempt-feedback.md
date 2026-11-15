---
id: CIC-1794764263401395
title: Align DPI-38 CLI smoke with applied-attempt feedback
kind: bug
status: done
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
closed_at: "2026-11-15T17:56:30Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Verified source 684dd2f094d9cc73fe82e21efec78842b7e94beb: real CLI loopback no-credit and independent oracle tests 2 passed; existing oracle tests 7 passed; strict CLI Clippy, locked metadata, architecture health, just ci-gates, task contracts, and independent review passed. Positive Linux capture and external warmup remain explicitly unrun locally."
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

At `684dd2f094d9cc73fe82e21efec78842b7e94beb`, two new tests and seven existing packet-oracle tests passed. The negative test used the real CLI and two loopback HTTP streams: selected attempts and plain forwarding, with no success or failure credit. CLI all-target strict Clippy, locked metadata, architecture health, `just ci-gates`, and task validation passed. The independent reviewer found no actionable defects. The final panic cleanup uses the existing process-stop pattern.

The registered positive capture selector now uses the HTTP filter, requires two complete byte-and-sequence split flows and exactly two success receipts, and retains host-autolearn persistence. Negative capture artifacts are retained separately. Linux positive capture was not run on Darwin: split is unsupported there and the existing autolearn case starts five external warmup probes. This local limit is not a claim of hosted CI success or external compatibility. Production code, dependencies, baselines, and goldens are unchanged by this task.
