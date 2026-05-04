---
title: Epic - Native hotspot decomposition
type: epic
status: backlog
area: epic
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Epic - Native hotspot decomposition #repo/RIPDPI #area/epic #status/backlog 🔼

## Goal

Split the three oversized hot files by responsibility so future protocol
and evasion work doesn't serialize through the same three modules. Extract
a first-class internal `ActionPlan` IR in the Rust runtime as a side
benefit that makes planner, emitter, and fallback logic independently
testable.

## Why now

Audit quantifies the concentration: `desync.rs` 1538 LOC mixing planning,
fallback choice, fake-packet construction, TTL-sensitive send logic, and
plan execution; `linux.rs` 1557 LOC mixing socket options, protect logic,
raw sends, TCP repair, TTL capture, and low-level packet mutation;
`RipDpiProxyJsonCodec.kt` 708 LOC mixing schema, migration, validation,
and rewrite. Every future change piles on unless we refactor now.

## Key decisions

- **Split by responsibility, not by arbitrary LOC.** `desync.rs` →
`planner / emitters / fallback_classifier / fake_packet`. `linux.rs` →
`sockopts / protect / raw_send / tcp_repair`. Codec → `schema /
migration / validation / rewrite`.
- **ActionPlan IR first.** The planner module becomes the natural home
for a typed plan; emitter/platform code consumes it. Keep the IR
internal to the Rust runtime initially — no JNI exposure.
- **Preserve behavior.** Existing integration and fuzz coverage must stay
green throughout. Each split is a pure refactor.

## Scope

- **In scope:** `desync.rs`, `linux.rs`, `RipDpiProxyJsonCodec.kt`,
introduction of a first-class `ActionPlan` IR.
- **Out of scope:** oversized Kotlin UI screens (separate cleanup track);
any behavior change that isn't required by the split.

## Ship definition

- [ ] `desync.rs` and `linux.rs` each sit comfortably below a sustainable
    LOC budget per resulting file (target: <800 LOC per file post-split).
- [ ] `RipDpiProxyJsonCodec.kt` modules are each <300 LOC.
- [ ] `config/static/file-loc-baseline.json` updated; no new oversized
    files added by the split.
- [ ] `ActionPlan` IR exists, has unit tests, and at least one call site
    is migrated as a pilot.
- [ ] No existing test regresses.

## Child tasks

**Rust split**
- [[Decompose desync.rs by responsibility]]
- [[Decompose linux.rs by responsibility]]
- [[Extract native ActionPlan IR]]

**Kotlin split**
- [[Decompose RipDpiProxyJsonCodec]]

Child tasks roll up via the TaskNotes relationships view on this note.

## Risks / open questions

- IR shape: what belongs in the `ActionPlan` vs left to emitters? Prototype
before committing to a public module surface.
- Fuzz coverage may bit-exactly match current structures — verify the fuzz
harness still hits the moved code after each split.

## Links

- [[ripdpi-android]]
- [[ripdpi-android-audit-2026-04-20]] §10, Highest-ROI #3
- Child issues: 4
