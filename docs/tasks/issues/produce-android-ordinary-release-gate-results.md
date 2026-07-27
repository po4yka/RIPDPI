---
title: Produce Android ordinary release-gate results locally
type: task
status: doing
area: testing
priority: high
owner: Codex release evidence completion
parent: null
blocks: []
blocked_by: []
created: 2026-07-22
updated: 2026-07-27
---

## Goal

Replace hand-authored or missing Android ordinary DNS, IPv6, and kill-switch
results with a deterministic local producer that fails closed until results can
be derived by checked-in code from source-, APK-, device-, and raw-artifact
evidence.

## Scope

- Own only the 11 `ordinary-results` gates for `android-client-release`.
- Reject missing, stale, partial, skipped, unapproved, or self-declared PASS
  evidence and report the exact missing oracle without inventing a green result.
- Emit canonical `dns_ipv6_killswitch_results_v1` JSON accepted by the existing
  release-gate checker.
- Keep physical packet-path claims separate from local unit-contract support.

## Ship definition

- Unit tests cover the exact gate inventory, deterministic output, dirty source,
  incomplete results, legacy and structured forged PASS, stale-output
  replacement, and checker-compatible no-ship reasons.
- The local command binds the exact clean source commit and cannot emit PASS
  until a checked-in raw-artifact verifier exists.
- Gates without a real approved oracle emit a structured FAIL and make the
  command fail closed.

## Work log

- 2026-07-27: Release evidence completion lane now owns all seven ordinary
  action oracles for `v0.1.4`. Completion requires source-owned parsing of each
  private action receipt, packet capture, and route snapshot; adversarial tests
  for forged, partial, stale, cross-action, and contradictory bundles; and a
  real exact-SHA physical run. The lane may enable PASS only from those parsed
  facts and may not accept caller-authored verdicts or counters.
- 2026-07-27: Reopened for the `0.1.4` release. The remediation lane owns the
  seven source-owned semantic action oracles, adversarial fixtures, physical
  capture integration, and exact-SHA release-workflow handoff without weakening
  the existing fail-closed checker.
- Added a deterministic producer for the exact 11 Android ordinary gates. It
  emits canonical structured no-ship results from an exact clean commit.
- Removed the arbitrary collector/plugin and self-attested hash design after
  adversarial review showed that manifest counters and copied artifacts were
  forgeable. The checker rejects every ordinary PASS, including legacy string
  PASS and objects with recomputed public hashes.
- Structured all-FAIL results remain checker-compatible, and checker violations
  retain the exact producer reason.
- Remaining release blocker: a checked-in, audited verifier must derive each
  observation from raw packet and route artifacts before PASS can be enabled.
