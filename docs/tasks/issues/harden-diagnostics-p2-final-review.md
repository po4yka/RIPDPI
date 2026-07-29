---
title: Harden diagnostics P2 final review findings
type: task
status: doing
area: diagnostics
priority: high
owner: Codex diagnostics P2 coordinator
parent: null
blocks: []
blocked_by: []
created: 2026-07-29
updated: 2026-07-29
---

## Goal

Resolve every actionable correctness and cancellation finding from the final
P2 review before integrating the diagnostics evidence series into `main`.

## Ownership lanes

1. **Policy handover validation** owns dependency-key validation before ACK and
   pruning in `PolicyHandoverEventStore`.
2. **Process exit and event window** owns terminal-exit consumption and the
   transition-excluding bounded root-cause query.
3. **Runtime classifier and producers** owns cross-layer evidence semantics and
   production-reachable OEM, MTU, relay, and protect evidence contracts.
4. **Relay UDP probe** owns delayed/out-of-order datagram correlation and typed
   exception handling.
5. **Background acceptance** owns baseline cancellation, startup reconciliation,
   incomplete evidence retention, and first-terminal-wins persistence.
6. **Scan startup cancellation** owns non-cancellable bridge/session cleanup
   when cancellation occurs during native startup.
7. **Verifier** owns recombination, rebasing onto latest `origin/main`, privacy,
   cancel-safety, static analysis, contract gates, and final review.
8. **Post-review scan startup** owns transaction-wide cleanup across every
   startup suspension boundary and categorical failure summaries.
9. **Post-review relay capability** owns hardened correlation for the basic
   SOCKS UDP capability probe, including contaminant and deadline handling.
10. **Post-review process exit** owns retry-correlation validation, temporal
    eligibility, and neutral handling of expected user lifecycle exits.
11. **Post-review acceptance startup** owns pending-generation validation,
    reconciliation ordering, bounded cancellation persistence, categorical
    logging, and startup cancellation propagation.

## Boundaries

- No baseline, golden, suppression, hidden Android API, archive-schema, wire,
  dependency, or signing changes.
- No raw device, network, endpoint, resolver, policy, or exception values may be
  persisted or exported.
- Each lane must fail closed on malformed, stale, missing, or contradictory
  evidence and must preserve cancellation semantics.
- No device evidence is claimed from JVM, Robolectric, lint, or host tests.

## Acceptance

- Every actionable initial and post-recombination review finding has a
  regression test and root-cause fix.
- Each lane is an atomic Conventional Commit series in an isolated worktree.
- The recombined tree passes module JVM tests, both app flavor JVM suites,
  AndroidTest Kotlin and Hilt compilation, full static analysis, architecture
  health, locked Cargo metadata, task-board, translation, PMTUD, and diff gates.
- A fresh correctness, privacy, and cancel-safety review has no actionable
  findings before fast-forward integration and push.
