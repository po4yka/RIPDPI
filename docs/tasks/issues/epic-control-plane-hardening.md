---
title: Epic - Control-plane hardening
type: epic
status: done
area: epic
priority: critical
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-29
---

- [x] #task Epic - Control-plane hardening #repo/RIPDPI #area/epic #status/done 🔺

## Goal

Replace the current fragile catalog download path (same-origin checksums, no anti-rollback, non-atomic writes, setting-triggered refreshes) with a signed, rollback-resistant, atomic, TTL-gated control plane. Outcome: an old valid-signed payload can't downgrade the client, a mid-write crash can't corrupt the cache, and unrelated settings edits don't hit the network.

## Why now

The 2026-04-20 audit rated strategy/host catalog trust as the single weakest link in the project's security story. Fixing this first prevents building new features on top of a control plane that may ship fragile.

## Key decisions

- **Signed manifests for host packs** using the same app-trusted key infra as strategy packs (decide reuse vs new key before implementation).
- **Monotonic sequence + freshness** inside the signed envelope for both pack types; reject stale on principle, allow rollback only via an explicit local override.
- **AtomicFile (or temp-file + fsync + rename)** for every cache write; a torn file must never appear at the canonical path.
- **Refresh is scheduled, not eager.** Trigger on the narrow tuple `(channel, refreshPolicy, pinnedPackId, pinnedPackVersion)` with `distinctUntilChanged` + TTL + backoff.

## Scope

- **In scope:** strategy-pack refresh discipline, host-pack signature model, anti-rollback, atomic snapshot writes, typed degradation telemetry.
- **Out of scope:** transport/runtime changes, operator UX beyond the degradation-reason surfacing.

## Ship definition

- [x] Unsigned or invalid-signature host-pack payload is rejected with a typed error — `HostPackVerifier` / `StrategyPackVerifier` (ECDSA SHA256 against `AppTrustedSigningKeyResolver`), invoked on every refresh in `HostPackCatalogRepository` / `StrategyPackRepository`; failures surface as `CacheDegradation.SignatureInvalid`.
- [x] Older-sequence strategy-pack payload is rejected without the debug local override — `StrategyPackRepository.enforceAntiRollbackPolicy()` rejects sequence ≤ accepted via `StrategyPackRollbackRejectedException` unless `allowRollbackOverride = true`; covered by `AssetStrategyPackRepositoryRefreshPolicyTest`.
- [x] Process kill mid-write of either cache leaves the prior snapshot intact and readable — `AtomicTextFileWriter` uses Android `AtomicFile` (`startWrite` → `finishWrite`, `failWrite` on error), used by both repositories.
- [x] Unrelated app-setting edits produce zero strategy-pack network I/O (measured in a unit test) — refresh is gated on the `StrategyPackRefreshKey` tuple via `StrategyPackSettingsObserver`'s `distinctUntilChanged`, with TTL + backoff in `StrategyPackRefreshSchedule`.
- [x] Cache parse failures surface as typed `CacheDegradation` reasons, not silent empty state — `CacheDegradation` sealed class (`Missing` / `SchemaMismatch` / `SignatureInvalid` / `Corrupt`) carried by the repositories' load results.

Verified 2026-05-29: `:core:service:testDebugUnitTest` control-plane suites
(`*StrategyPack*`, `*HostPack*`, `*AtomicTextFile*`) pass; `CacheDegradation`
lives in `core/data/model`.

## Child tasks

### Catalog control-plane (ship-definition) — done

These were the original children; all are now implemented (see the Ship
definition evidence above). Their individual task files were removed in the
2026-05 task-board cleanup once the work landed:

- Tighten strategy-pack refresh discipline — done (`StrategyPackRefreshKey` + `distinctUntilChanged` + TTL/backoff).
- Sign host-pack manifests with app-trusted keys — done (`HostPackVerifier`).
- Add anti-rollback to strategy-pack updates — done (`enforceAntiRollbackPolicy`).
- Make cache snapshot writes atomic — done (`AtomicTextFileWriter` / Android `AtomicFile`).
- Surface typed cache-degradation reasons — done (`CacheDegradation` sealed class).
- Spike signed route-pack schema for direct-vs-relay policy — closed.

### Native-surface hardening — live child task files (parented here)

- [[Pin BoringSSL Reality FFI symbols with a build-time existence check]] — **done** (2026-05-29). Exact-version pins + link-time symbol existence check; contract documented in `proxy-engine.md`. vless 81/0 tests green.
- [[Introduce ProtocolVersion enum and version-mismatch probe diagnostic]] — **done** (2026-05-29). Typed version enums across vless/tuic/mtproto, `version_probe` classifier, distinct `Tuic`/`ShadowTls` version-mismatch failure classes. 92+ tests green, clippy clean.
- [[Gate fake-SNI cert-bypass behind allow_insecure_sni flag with telemetry]] — **partial**. The security objective is shipped and verified: `fake_sni` is refused with `PermissionDenied` unless `allow_insecure_sni == true`, covered by ws-tunnel unit tests. The remaining items — a `ws_tunnel.fake_sni_active` telemetry counter and service-layer import rejection — are a coupled cross-layer follow-up (new `WsTunnelSettings.allow_insecure_sni` config-schema field + `RuntimeTelemetrySink` method + diagnostics export); the counter cannot meaningfully fire until that plumbing lands (the adapter hardcodes `allow_insecure_sni = false`). Tracked in that task.

Child tasks roll up via the TaskNotes relationships view on this note.

## Status note (2026-05-29)

The epic ship-definition (the signed, rollback-resistant, atomic, TTL-gated
catalog control plane) is **fully implemented and verified**. Two of the three
later native-hardening child tasks are done; the third (fake-SNI gating) has
its security objective shipped and verified, with an observability follow-up
(telemetry counter + import-time rejection) deliberately split out as a
config-contract + telemetry-trait change. No work on the catalog control plane
or the protocol-version / BoringSSL hardening remains.

## Dependencies

- Unblocks: Add control-plane rollback attempt test and Add cache-corruption regression test under [[Epic - Orchestration test posture]].
- Unblocks: [[Build CensorLab-style offline strategy-pack pipeline]] under [[Epic - Privacy-preserving strategy learner]] (generated packs must fit the hardened signed format).

## Risks / open questions

- Signing model for host packs: reuse the strategy-pack key or issue a new one? Decide before the signing task lands.
- Rollback override UX: settings toggle, CLI flag, or debug-only? Keep it boring and hard to find by accident.
- `autoArchiveDelay` coupling to status changes — ensure degraded-source telemetry doesn't accidentally auto-archive the related notes.

## Links

- [[ripdpi-android]]
- Child issues: 3
