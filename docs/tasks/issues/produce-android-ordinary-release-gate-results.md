---
title: Produce Android ordinary release-gate results locally
type: task
status: doing
area: testing
priority: high
owner: Codex ordinary semantic oracle lane
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
  until both the checked-in semantic verifier and a source-owned physical
  producer with an attestation path exist.
- Gates without a real approved oracle emit a structured FAIL and make the
  command fail closed.

## Work log

- 2026-07-27: A fourth integration review found that a later duplicate `tun0`
  block could contradict the first accepted block. Address outputs now require
  exactly one block for the declared VPN interface; a dedicated adversarial
  fixture rejects `tun0 → wlan0 → tun0` ambiguity.
- 2026-07-27: A third integration review found two cross-record false
  positives. Address facts are now scoped to the declared VPN interface block,
  so an empty `tun0` cannot borrow addresses from `wlan0`. Tunnel endpoint and
  port must now match on the same packet direction. Multi-interface and crossed
  endpoint/port adversarial fixtures cover both cases.
- 2026-07-27: A second integration review rejected an incomplete transition
  chain and weak active-interface evidence. Wi-Fi/LTE and sleep/wake now require
  transition/blocked observations before tunnel traffic, a re-established route
  before the post-tunnel probe, and the outcome marker last. Active VPN phases
  also require the declared interface to be UP with an IPv4 address. New tests
  reject each reordered chain and a DOWN/addressless active TUN.
- 2026-07-27: The independent integration review found and blocked three
  semantic gaps before merge: packets outside the marker interval were ignored,
  action artifacts were not causally ordered, and the combined address output
  was not cross-checked. The follow-up now evaluates the entire action window,
  binds event/probe/DNS/route/marker order, validates the full sleep interval,
  and requires combined and IPv6-specific address views to agree. Dedicated
  adversarial cases reproduce every rejected bypass.
- 2026-07-27: Implemented all seven source-owned semantic action oracles. They
  strictly parse canonical action receipts, raw route-command snapshots, and
  bounded classic PCAP; bind every artifact to the action window, correlation
  ID, source SHA, and app/test APKs; and derive the 11 ordinary gate semantics.
  Negative and adversarial fixtures cover each action boundary, caller-authored
  verdicts/counters, cross-action copies, stale correlations, contradictory
  probes and packets, IPv6 leaks, unexpected underlay traffic, missing tunnel
  activity, duplicate/missing markers, truncation, and forged PASS provenance.
- 2026-07-27: Kept public results fail-closed after semantic success. The
  verifier records action proof digests with `semanticVerified: true`, but the
  producer returns all 11 gates as structured FAIL with
  `SOURCE_OWNED_PHYSICAL_PRODUCER_UNAVAILABLE` and `productionReady: false`
  until source-owned physical collection and attestation are implemented.
- 2026-07-27: Started the isolated `codex/release-014-ordinary-oracles`
  worktree from `origin/main` at `519ec5183fd416e35898c55b19149ee117d06980`.
  This lane owns the seven source-owned action oracles, their exact raw receipt,
  PCAP, and route-snapshot contracts, plus negative and adversarial tests. It
  does not own physical capture execution, producer allowlisting, release
  integration, or tag publication.
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
