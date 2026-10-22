---
id: TRN-1792659369802828
title: Verify payloads before automatic selector failover
kind: feature
status: review
area: transport
priority: high
owner: Client runtime implementer
parent: null
blocked_by: []
spec_mode: required
openspec_change: payload-aware-selector-failover
created: 2026-10-22
updated: 2026-10-22
---

## Goal

Import an explicit payload-failover policy and switch the active selector only
after complete authenticated payload transfers through real candidate relays.

## Acceptance criteria

1. The shared bundle extension is validated and persisted without adding unsupported fields to ordinary sing-box outbounds.
2. Two independent HTTPS controls require exact bounded length and SHA-256; short bodies, redirects, bad certificates, wrong hashes and timeouts never qualify a candidate.
3. Consecutive failures/recovery, cooldown and bounded backoff prevent flapping; network changes invalidate observations and all-failed candidates never select direct egress.
4. Import, real HTTP body verification and virtual-clock selector tests pass; scoped Android module gates and static analysis are observed.
5. Delivery alone is not deployment or filtered-network acceptance. A refusal-only implementation cannot close this feature.

Coordinated producer task: `po4yka/ripdpi-vpn-deploy#SCR-1792659157802819`.

## Ownership

- Client runtime implementer owns this isolated worktree: subscription importer and persisted policy, service payload verifier, app selector controller, their tests, mirrored bundle schema and this task/OpenSpec change.
- Deployment implementer owns the source schema and emitter in the sibling deployment repository. Synchronize the exact reviewed schema; do not edit their worktree.
- No JNI/protobuf/Rust/locale changes are planned. Shared-file ownership stays with this writer for the bundle mirror and selector model.

## Acceptance incident — 2026-10-22

The prior closure was premature. Final CI 39772247126 on
`b0b48501b738295bc0597ab5fb6815c0975d477f` failed service-start instrumentation
on API 27, 33, 35, 36 and 37: all 24 ServiceLifecycleIntegrationTest cases
timed out before Running, and two SelectorRuntimeInstrumentedTest native utility
cases timed out before measurement on each API. The deferred group snapshot contract left selector lifecycle
preparation waiting for a seed before its first validated catalogue load.

The three closure commits were reverted with ordinary commits, preserving their
receipts in history. This task was returned to doing pending explicit cold-start loading,
read-failure propagation, affected JVM/static checks and the complete required
instrumentation matrix results. No timeout, assertion or safety gate was weakened.

## Renewed acceptance — 2026-10-22

The complete required CI matrix 39792086326 passed on `6ec802d16d19a5c0f81f817be8cfc8f1205d29b5`,
including all five API levels, Full/Simple JVM tests, coverage and static analysis.
PR 645 rebase-merged the exact same complete tree `8d205fb96495d83294b14ef9bd5f90dc49f98d71`
as `33046c961cf4a501a1a8ac35243c6a171424fdc3`. The cold-start incident is resolved;
physical-device, filtered-network and deployment acceptance remain unclaimed.
This follow-up records review, archival/terminal and purge in separate commits.
