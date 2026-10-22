---
id: DAT-1792683530998740
title: Commit subscription AWG rotations under current group ownership
kind: bug
status: done
area: data
priority: high
owner: Client subscription maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: atomic-subscription-awg-refresh
created: 2026-10-22
updated: 2026-10-22
status_detail: Merged source ede4099 has an identical full tree to tested b5b09; CI 39815952976 and all five emulator matrices passed.
closed_at: "2026-10-22T17:35:01Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Merged source ede4099f5e53023c03e4e6875cdb8521cb627d56 has the identical complete tree as tested b5b09af6ea93f5c076232e5d97529aa79ff93396. CI 39815952976 attempt 1 passed all required checks, five Full/Simple API matrices, JNI strategy and real Xray TUN acceptance. Local affected-module/app JVM and static checks passed; no physical-device or deployment result is claimed.
---

## Goal

Reject a stale subscription response before it can rotate AWG rows or credentials, and commit accepted AWG and group updates under the existing mutation owner.

## Acceptance criteria

- Delayed valid AWG responses cannot mutate a changed source, edited group, deleted group, or newer accepted generation.
- Accepted refresh rotates the existing AWG identity while preserving editor-owned key and network policy.
- The current group comparison and all writes share the existing recovery mutex; no check/write gap or nested recovery acquisition exists.
- Cancellation and write failure retain an encrypted journal that recovers the accepted full generation before another mutation.
- Affected module tests, static analysis, task contracts, architecture checks and full required CI pass before closure.

## Ownership

The client subscription maintainer owns app subscription coordination/tests, core-data AWG and recovery implementation/tests, runtime-state journal family, and this task/spec. Other worktrees and the pending acceptance branch are untouched. Shared mutation contracts have one writer; independent review is read-only.

## Scope and incident

Review found that `fetchAndParse` saved valid AWG profiles before the selector group compare-and-set. A delayed response could return `Superseded` after rotating credentials. The earlier offline-reserve design explicitly excluded AWG multi-store transactions; this bug extends that ownership guarantee without rewriting prior acceptance history. The source fix and prior feature acceptance were merged together in PR #647 after the full required CI passed. No deployment, credential use or physical-device acceptance is included.

## Accepted evidence

Source `ede4099f5e53023c03e4e6875cdb8521cb627d56` has the identical complete tree as tested `b5b09af6ea93f5c076232e5d97529aa79ff93396`. CI run `39815952976` attempt 1 passed all required checks, including Full/Simple instrumentation on API 27/33/35/36/37 and API 35 JNI/real Xray-provider TUN acceptance. Local affected-module, Full/Simple app and static gates passed; the verification file preserves exact counts, up-to-date reuse and the initial corrected Detekt failure. Independent source review approved the implementation; cloud review was unavailable because of account quota. No physical-device, filtered-path or server deployment result is claimed.
