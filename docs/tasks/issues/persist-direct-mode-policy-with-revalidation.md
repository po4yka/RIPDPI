---
title: Persist direct-mode policy with revalidation
type: task
status: done
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-diagnostic-state-machine
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-29
---

- [x] #task Persist direct-mode policy with revalidation #repo/RIPDPI #area/diagnostics #status/done 🔼

## Summary

Phase 5 of the diagnostic. Policy is pinned with a TTL and invalidated on environmental change; after 3 consecutive failures re-runs the full diagnostic.

## Plan reference

ripdpi-android-direct-mode-plan-2026-04-20 "Phase 5 — Persistence and revalidation".

## Progress

Verified 2026-05-28 against the current persistence path:

- confirmed direct-path policy is stored with a 7-day TTL;
- runtime ignores unconfirmed authority policy records instead of blindly replaying one-off diagnostics results;
- three consecutive revalidation failures now retire the cached policy entry from runtime use;
- `NO_DIRECT_SOLUTION` entries now age out when their cooldown expires instead of living forever in the injected direct-path capability set.

Verified 2026-05-29. The remaining revalidation triggers are now implemented
on the `ServerCapabilityRecord` direct-policy gate (the layer where TTL/cooldown
already live), covered by `ServerCapabilityDirectPolicyTest`:

- `observedAsn` / `echCapable` / `httpsRrExpiresAt` are persisted on the record
  and carried through `mergeCapabilityRecord`;
- `isInvalidatedByEnvironment(DirectPolicyEnvironment, now)` drops a cached
  policy when the ASN changed or the ECH capability flipped (each trigger fires
  only when both stored and current values are known — never a false drop);
- HTTPS/SVCB TTL expiry is self-contained in `isFreshDirectPolicy` (stored
  `httpsRrExpiresAt` vs now) and is fully live;
- the direct-path write now uses `commit()` (atomic + synchronous) so a confirmed
  policy survives an LMK SIGKILL mid-transition;
- the TTL window is anchored on `policyConfirmedAt`, so a Phase 6 variant
  rotation (which bumps `updatedAt` but preserves the confirmation) does not
  extend the policy's lifetime.

The read path (`ConnectionPolicyRuntimeContextAssembler`) now consults the
environment-aware `isRuntimeUsableDirectPolicy(now, environment)`. The current
ASN / per-host ECH have no reliable hot-path source yet, so they are passed as
unknown (a safe no-op); feeding them live is tracked under the epic's "wire the
pure orchestrator to production probe executors" item.

## Acceptance criteria

- [x] TTL: 7 days default, configurable later if needed.
- [x] Invalidate on ASN change — `isInvalidatedByEnvironment` ASN trigger + `observedAsn` field, unit-tested (live current-ASN sourcing tracked separately).
- [x] Invalidate on access-type change (wifi ↔ cellular).
- [x] Invalidate after 3 consecutive failures.
- [x] Invalidate when HTTPS/SVCB TTL expires or ECH capability changes — SVCB-TTL expiry live in `isFreshDirectPolicy`; ECH-change trigger + `echCapable` field, unit-tested.
- [x] Atomic write — direct-path capability persisted via durable atomic `commit()` (SharedPreferences single-file write; survives LMK SIGKILL).
- [x] Phase 6 rotation triggers only within the same policy entry — TTL anchored on `policyConfirmedAt`, so a rotation does not reset the TTL.

## Links

- Implement direct-mode diagnostic orchestrator Phases 1-4 (closed task)
- Rotate successful family through variant neighborhood
- Make cache snapshot writes atomic
- [[Epic - Direct-mode diagnostic state machine]]
- ripdpi-android-direct-mode-plan-2026-04-20
