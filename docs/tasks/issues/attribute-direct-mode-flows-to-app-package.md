---
title: Attribute direct-mode flows to the owning app package
type: task
status: done
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-transport-policy-and-verdicts
blocks: []
blocked_by: []
created: 2026-05-29
updated: 2026-05-29
---

- [x] #task Attribute direct-mode flows to the owning app package #repo/RIPDPI #area/diagnostics #status/done 🔼

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

- [x] Resolve a flow's owning UID via `ConnectivityManager.getConnectionOwnerUid`
      and map it to a package name + `longVersionCode` (`FlowAppAttributionStore`).
- [x] Attribute attributable flows; leave them unattributed when attribution is
      unavailable (multi-package / shared UID, lookup failure, API < 29) — the
      learner treats unattributed flows conservatively. *Realised via a Kotlin-side
      join rather than `DirectPathLearningSignal` fields — see the implementation
      note: the producer (tun2socks) and consumer (proxy policy) are separate
      `.so`s, so the join cannot ride the native signal.*
- [x] Honor `network-fingerprint-privacy.md`: the package name lives only in the
      in-memory `FlowAppAttributionStore` and never enters Rust, the telemetry
      snapshot, logs, or any persisted/exported artifact.
- [x] Eager package-version invalidation hook (extends the
      `DnsPathPreferenceInvalidator` `ACTION_PACKAGE_REPLACED`/`REMOVED` receiver)
      that calls `FlowAppAttributionStore.invalidateOnAppUpdate`; the version-keyed
      lookup also reverts lazily.
- [ ] Cross-process persistence of the per-app memory — deferred; the in-memory
      lifetime is acceptable (attribution re-resolves quickly on a new session).
      Re-evaluate only if field data shows it matters.

## Implementation note (2026-05-29)

Implemented across the native + Kotlin stack. A `cargo tree` check proved the flow
**producer** (`ripdpi-tunnel-core` in `libripdpi-tun2socks.so`) and the learning-signal
**consumer** (`ripdpi-runtime-policy` in `libripdpi.so`) are separate `.so`s, so a Rust
`static` cannot bridge them and the attribution **join is done in Kotlin** (the one shared
process layer). End-to-end pipeline:

1. `ripdpi-tunnel-core` calls `ripdpi_flow_app_attribution::note_flow(proto, app_src, dest)`
   at TCP admission and UDP-association birth (hot-path-safe: mutex + queue push, deduped by
   destination); `evict_flow(dest)` on session/association close.
2. `ripdpi-tunnel-android::flow_attribution` runs a background worker draining the queue and
   calls Kotlin `FlowAttributionBridge.noteFlow(...)` over JNI, off the hot path
   (`jniRegister/UnregisterFlowAttribution`, generation-guarded; registered around the tunnel
   session lifecycle via `Tun2SocksTunnel`).
3. Kotlin `FlowAppAttributionStore` resolves UID → package → `longVersionCode`
   (`getConnectionOwnerUid`, API 29+, conservative on shared/unknown UID) and stores
   `ipSetDigest(dest) → AppAttribution` in a `@Singleton` in-memory map.
4. `DirectPathPolicyLearner` joins each learning signal against the store by
   `signal.ipSetDigest` and drives the per-app `NoTcpFallbackAppMemory`.

**Correlation key** is the destination IP, hashed with the same `direct_path_ip_set_digest`
algorithm on both sides (Kotlin digest pinned to Rust vectors in `FlowAppAttributionStoreTest`).
Single-destination direct-path flows correlate; multi-IP target sets or two apps to one
destination fall back to unattributed (conservative) — a documented limitation.

`@Keep` on `FlowAttributionBridge.noteFlow` is load-bearing (JNI-only method; R8 would strip it).

### Remaining: device verification (cannot run in a headless env)

The unit layers are green (native `cargo nextest`, Kotlin model/engine/service tests). The
live JNI hop, R8 `@Keep` behavior, and `getConnectionOwnerUid` resolution are only verifiable
on an emulator/device. Confirm on device: (a) a known app's flow is attributed and its per-app
`NO_TCP_FALLBACK` verdict fires; (b) no package name appears in a generated diagnostics export.

## Links

- [[Epic - Direct-mode transport policy and verdicts]]
- `core/data/model/.../NoTcpFallbackAppMemory.kt` — the live memory this feeds.
- `core/service/.../DirectPathPolicyLearner.kt` — the consult site.
- `.claude/rules/network-fingerprint-privacy.md`
- `.claude/rules/vpnservice-protect-invariant.md`
