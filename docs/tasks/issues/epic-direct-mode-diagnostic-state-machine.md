---
title: Epic - Direct-mode diagnostic state machine
type: epic
status: todo
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-28
---

- [ ] #task Epic - Direct-mode diagnostic state machine #repo/RIPDPI #area/epic #status/todo ⏫

## Goal

Orchestrate the subsystems (DNS classifier, transport policy, TLS family engine, strategy learner, owned-stack) into a single diagnostic that returns one of three honest verdicts: `TRANSPARENT_WORKS`, `OWNED_STACK_ONLY`, `NO_DIRECT_SOLUTION`. The integration epic that makes the rest of the direct-mode stream user-visible.

## Why now

The subsystem epics are useful individually, but the user-facing capability is "tell me whether I can reach this host directly, and if so, how." Without this epic, five good subsystems produce no product.

## Key decisions

- **Sealed `DiagnosticResult` taxonomy** with structured reason codes:

```text
DiagnosticResult = TRANSPARENT_WORKS
               | OWNED_STACK_ONLY
               | NO_DIRECT_SOLUTION { reason: IP_BLOCKED | ... }
```

- **Phase 0 passive observation before active probing.** Extract what we can from the last failed flow (DNS outcome, fail phase, error-page shape) so we don't probe from zero every time.
- **Six-phase flow** matching the plan: 0 passive obs → 1 DNS class → 2 transport class → 3 ranked arms → 4 execute with early stop + confirm → 5 persist with revalidation → 6 rotate within winner's neighborhood.
- **Per-class arm list is fixed** (see ripdpi-android-direct-mode-plan-2026-04-20 Phase 3), then reranked by the learner's local priors.
- **TTL-gated persistence.** 7-day default; invalidate on ASN change, access-type change, 3 consecutive failures, HTTPS/SVCB TTL expiry, or ECH capability change.
- **Hard separation of product modes.** Transparent-mode arms (A3–A8) and owned-stack arms (A9–A10) execute through different code paths with different invariants.

## Scope

- **In scope:** direct-mode product-mode boundary, `DiagnosticResult` types and classification taxonomy, Phase 0 passive observation, Phases 1–4 orchestration, Phase 5 persistence and revalidation, Phase 6 variant rotation, integration tests per result class.
- **Out of scope:** subsystem internals (owned by the other epics).

## Verified current state

The repo-owned direct-mode state machine is now substantially more real, but the epic is still not fully closed:

- typed direct-mode verdicts are now persisted and surfaced end to end through the diagnostics engine wire contract and summary layer;
- the positive `TRANSPARENT_WORKS` outcome is now visible instead of being dropped on the floor by the display-summary path;
- persisted `strategyRecommendation` is available again to the Home audit workflow, so the subsystem outputs now survive finalization and storage;
- diagnostics finalization now consults the last stored authority policy before pinning a new verdict, which gives the current implementation a lightweight Phase 0 passive prior from the last confirmed flow;
- persisted direct-mode policy now honors `confirm-before-pin`: transparent / owned-stack outcomes need corroborating evidence or a matching prior, while negative outcomes need repeated active failures before they become stored policy;
- Phase 5 persistence is partially implemented in repo scope: stored authority policy has a 7-day TTL, runtime ignores unconfirmed entries, and three consecutive revalidation failures retire the cached policy.
- Phase 1 through Phase 4 now have a pure `DirectModeOrchestrator` dispatcher with hard `AttemptBudget` enforcement and a source-backed per-class candidate-arm table.

Still open: wiring the pure orchestrator to the production probe executors, emitting a final `OrchestratorResult.verdict`, ASN / HTTPS-RR-specific invalidation triggers, and deterministic integration coverage for every result class.

## Ship definition

- [x] One diagnostic run produces exactly one `DiagnosticResult` variant, each with a structured reason.
- [x] Attempt budget hard-enforced in `DirectModeOrchestrator` and covered by `AttemptBudgetEnforcementTest`.
- [x] Per-class arm lists match the plan exactly and are covered by `PerClassArmListTest`:
- `DNS_BLOCK`: A1, A3, A4, A5, A6, A10, A9
- `SNI_TLS_SUSPECT`: A3, A5, A6, A7, A8, A10, A9
- `QUIC_BLOCK_SUSPECT`: A3, A4, A5, A6, A9
- `IP_BLOCK_SUSPECT`: A10, A9
- `UNKNOWN`: A1, A3, A4, A5, A9
- [x] Phase 4 success requires a confirmation request before pinning in the repo-owned persistence path.
- [ ] Persisted verdict invalidates on every revalidation trigger.
- [x] Integration tests cover every result class on a deterministic harness (no sleep-based waits) — `DiagnosticResultClassIntegrationTest` (frozen clock + scripted executor); the orchestrator now emits a non-null `OrchestratorResult.verdict`.

## Child tasks

**Boundary and types**
- Define transparent vs owned-stack mode boundary (closed task)
- Define DiagnosticResult and classification taxonomy

**Phases**
- [[Implement Phase 0 passive observation from last flow]]
- Implement direct-mode diagnostic orchestrator Phases 1-4 (closed task)
- [[Persist direct-mode policy with revalidation]]

**Integration tests**
- [[Add integration tests per diagnostic result class]]

**Remediation and handoff**
- Replace generic relay suggestion with transport-specific remediation ladder (closed task)

Child tasks roll up via the TaskNotes relationships view on this note.

## Remediation status

As of 2026-05-28, Diagnostics and Home branch from typed direct-mode verdicts into owned-stack, browser-camouflage relay, QUIC-heavy relay, or "no reliable relay hint" ladders instead of one generic relay fallback. The remaining gap in this epic area is config-side unification: relay preset suggestions still use their older heuristic path rather than the same shared remediation selector.

## Dependencies

Aggregates subsystem outputs from every direct-mode subsystem epic:

- [[Epic - Encrypted DNS and HTTPS SVCB classifier]] — Phase 1
- [[Epic - Direct-mode transport policy and verdicts]] — Phase 2
- Epic - Semantic TLS first-flight family engine — arms A5–A8
- [[Epic - Privacy-preserving strategy learner]] — Phase 3 ranking
- Epic - Owned-stack mode with Android 17 ECH — arms A9, A10
- [[Epic - Orchestration test posture]] — failure-injection harness for integration tests

## Risks / open questions

- Phase 4 `confirm_once` semantics: does a 2nd request to the same host really confirm, or could a CDN return-to-sender make it look successful? Define "stable success" precisely in the orchestrator task.
- "Known error-page" response-shape heuristic: starts conservative, tuned from real captures.

## Links

- [[ripdpi-android]]
- ripdpi-android-direct-mode-plan-2026-04-20 "Basic diagnostic: full state machine"
- Child issues: 7
