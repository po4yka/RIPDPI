---
title: Add ProxyGroup and Subscription entities to RIPDPI data layer
type: task
status: backlog
area: outbound
priority: critical
owner: unassigned
parent: epic-nekobox-subscription-and-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add ProxyGroup and Subscription entities to RIPDPI data layer #repo/RIPDPI #area/outbound #status/backlog 🔺

## Summary

Add a ProxyGroup abstraction (Basic + Subscription types) and a
SubscriptionBean child record so profiles can be organized, fetched, and
auto-refreshed from a subscription URL.

## Context

RIPDPI's current data layer has user relays and operator-shipped packs, but
no user-owned "group" that can hold dynamic subscription-sourced profiles.
This entity is the prerequisite for every other task in
[[Epic - NekoBox subscription and profile import]].

## Acceptance criteria

- [ ] `ProxyGroup` Protobuf message + Room projection with fields: id, name,
    type (`BASIC` | `SUBSCRIPTION`), order, isSelector,
    optional `Subscription` child.
- [ ] `Subscription` record with link, token, customUserAgent, autoUpdate,
    autoUpdateDelay, lastUpdated, updateWhenConnectedOnly, forceResolve,
    deduplication, subscriptionUserinfo, bytesUsed, bytesRemaining,
    expiryDate.
- [ ] Repository exposes add / update / delete / list flows and emits
    Kotlin `Flow` for UI binding.
- [ ] Existing user-relay data migrates cleanly into an ungrouped "default"
    group; no data loss.
- [ ] Schema is versioned; one forward migration is wired up under
    `core/diagnostics-data` or a new `core/profiles-data` module if the
    separation is cleaner.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`) — these files are the template for the schema shape:

- `app/src/main/java/io/nekohasekai/sagernet/database/ProxyGroup.kt` — `@Entity` fields: `id`, `userOrder`, `ungrouped`, `name`, `type` (`BASIC`/`SUBSCRIPTION`), `subscription` (embedded), `order`, `isSelector`, `frontProxy`, `landingProxy`. Port field-for-field but map to Protobuf DataStore or Room per RIPDPI's existing pattern (`DiagnosticsDatabase`).
- `app/src/main/java/io/nekohasekai/sagernet/database/SubscriptionBean.java` — field set: `link`, `token`, `customUserAgent`, `autoUpdate`, `autoUpdateDelay`, `lastUpdated`, `updateWhenConnectedOnly`, `forceResolve`, `deduplication`, `subscriptionUserinfo`, `bytesUsed`, `bytesRemaining`, `expiryDate`. Port verbatim.
- `app/src/main/java/io/nekohasekai/sagernet/database/ProxyEntity.kt` — the flat-bean-per-protocol pattern NekoBox uses (one nullable column per protocol). **Do NOT copy** this pattern; RIPDPI should use a discriminated union (Protobuf `oneof` or Kotlin sealed class) since the ProxyEntity bean-per-column layout is legacy.
- `app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt` — Room database wiring for reference; RIPDPI already has its own DB conventions.

**Adapt:** Field set, semantics. **Skip:** Kryo serialization (RIPDPI uses Protobuf), the bean-per-column ProxyEntity layout, `frontProxy`/`landingProxy` (proxy-chaining excluded per project note).

## Links

- [[Epic - NekoBox subscription and profile import]]
- [[ripdpi-android]]
