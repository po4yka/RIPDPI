---
title: Epic - Control-plane hardening
type: epic
status: todo
area: epic
priority: critical
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-23
---

- [ ] #task Epic - Control-plane hardening #repo/RIPDPI #area/epic #status/todo 🔺

## Goal

Replace the current fragile catalog download path (same-origin checksums,
no anti-rollback, non-atomic writes, setting-triggered refreshes) with a
signed, rollback-resistant, atomic, TTL-gated control plane. Outcome: an
old valid-signed payload can't downgrade the client, a mid-write crash
can't corrupt the cache, and unrelated settings edits don't hit the network.

## Why now

The 2026-04-20 audit rated strategy/host catalog trust as the single
weakest link in the project's security story. Fixing this first prevents
building new features on top of a control plane that may ship fragile.

## Key decisions

- **Signed manifests for host packs** using the same app-trusted key infra
as strategy packs (decide reuse vs new key before implementation).
- **Monotonic sequence + freshness** inside the signed envelope for both
pack types; reject stale on principle, allow rollback only via an
explicit local override.
- **AtomicFile (or temp-file + fsync + rename)** for every cache write; a
torn file must never appear at the canonical path.
- **Refresh is scheduled, not eager.** Trigger on the narrow tuple
`(channel, refreshPolicy, pinnedPackId, pinnedPackVersion)` with
`distinctUntilChanged` + TTL + backoff.

## Scope

- **In scope:** strategy-pack refresh discipline, host-pack signature
model, anti-rollback, atomic snapshot writes, typed degradation
telemetry.
- **Out of scope:** transport/runtime changes, operator UX beyond the
degradation-reason surfacing.

## Ship definition

- [ ] Unsigned or invalid-signature host-pack payload is rejected with a
    typed error.
- [ ] Older-sequence strategy-pack payload is rejected without the debug
    local override.
- [ ] Process kill mid-write of either cache leaves the prior snapshot
    intact and readable.
- [ ] Unrelated app-setting edits produce zero strategy-pack network I/O
    (measured in a unit test).
- [ ] Cache parse failures surface as typed `CacheDegradation` reasons,
    not silent empty state.

## Child tasks

**Refresh discipline**
- [[Tighten strategy-pack refresh discipline]]

**Signing and anti-rollback**
- [[Sign host-pack manifests with app-trusted keys]]
- [[Add anti-rollback to strategy-pack updates]]
- [[Spike signed route-pack schema for direct-vs-relay policy]]

**Crash-safe storage**
- [[Make cache snapshot writes atomic]]
- [[Surface typed cache-degradation reasons]]

Child tasks roll up via the TaskNotes relationships view on this note.

## Dependencies

- Unblocks: [[Add control-plane rollback attempt test]] and
[[Add cache-corruption regression test]] under
[[Epic - Orchestration test posture]].
- Unblocks: [[Build CensorLab-style offline strategy-pack pipeline]] under
[[Epic - Privacy-preserving strategy learner]] (generated packs must fit
the hardened signed format).

## Risks / open questions

- Signing model for host packs: reuse the strategy-pack key or issue a
new one? Decide before the signing task lands.
- Rollback override UX: settings toggle, CLI flag, or debug-only? Keep it
boring and hard to find by accident.
- `autoArchiveDelay` coupling to status changes — ensure degraded-source
telemetry doesn't accidentally auto-archive the related notes.

## Links

- [[ripdpi-android]]
- [[ripdpi-android-audit-2026-04-20]] §1, §2, §3, Highest-ROI #1
- Child issues: 3
