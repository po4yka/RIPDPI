---
title: Attribute direct-mode flows to the owning app package
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-transport-policy-and-verdicts
blocks: []
blocked_by: []
created: 2026-05-29
updated: 2026-05-29
---

- [ ] #task Attribute direct-mode flows to the owning app package #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Summary

The per-app-family `NO_TCP_FALLBACK` memory is implemented and load-bearing in the
live Kotlin policy path (`NoTcpFallbackAppMemory` consulted by
`DirectPathPolicyLearner`), but it only engages for *attributed* learning signals
— signals that carry `packageName` + `appVersionCode`. Today no layer populates
those fields: the native direct-path learning signal
(`DirectPathLearningSignal`, emitted via the runtime telemetry sink) carries only
`(authority, ipSetDigest, event, strategyFamily)`. So per-app suppression is
conservative-by-default and never fires on production telemetry.

This task closes that data-source gap by attributing each direct-mode flow to the
app package (and version) that owns it.

## Why this is separate from the epic

Per-flow app attribution in transparent TUN mode requires UID→package resolution
plumbed through the native runtime / JNI boundary and touches
`VpnService.protect()`-adjacent lifecycle assumptions. It is materially larger and
higher-risk than the policy-engine work in the parent epic (which is explicitly
scoped to *deciding* policy, not flow-ownership plumbing). The epic ships the
memory + invalidation; this task supplies the input that makes it fire live.

## Acceptance criteria

- [ ] Resolve a flow's owning UID via `ConnectivityManager.getConnectionOwnerUid`
      (or the native equivalent) and map it to a package name + `longVersionCode`.
- [ ] Populate `DirectPathLearningSignal.packageName` / `appVersionCode` for
      attributable flows; leave them null when attribution is unavailable
      (multi-package UID, shared UID, lookup failure) — the learner already treats
      null conservatively.
- [ ] Honor `network-fingerprint-privacy.md`: the package name is app identity,
      not a forbidden device identifier, but it MUST NOT leak into telemetry,
      logs, or persisted artifacts beyond the in-memory per-app policy decision.
- [ ] Add an eager package-version invalidation hook (mirror
      `DnsPathPreferenceInvalidator`'s `ACTION_PACKAGE_REPLACED` receiver) that
      calls `NoTcpFallbackAppMemory.invalidateOnAppUpdate` — the version-keyed
      lookup already reverts lazily, this makes it eager.
- [ ] Cross-process persistence of the per-app memory if the in-session lifetime
      proves too short in practice (re-evaluate after attribution lands).

## Links

- [[Epic - Direct-mode transport policy and verdicts]]
- `core/data/model/.../NoTcpFallbackAppMemory.kt` — the live memory this feeds.
- `core/service/.../DirectPathPolicyLearner.kt` — the consult site.
- `.claude/rules/network-fingerprint-privacy.md`
- `.claude/rules/vpnservice-protect-invariant.md`
