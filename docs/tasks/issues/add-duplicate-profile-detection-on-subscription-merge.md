---
title: Add duplicate-profile detection on subscription merge
type: task
status: backlog
area: outbound
priority: high
owner: unassigned
parent: epic-nekobox-subscription-and-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add duplicate-profile detection on subscription merge #repo/RIPDPI #area/outbound #status/backlog ⏫

## Summary

On subscription refresh, detect and collapse profiles that are byte-equal
except for display name, so periodic re-fetch does not duplicate the group.

## Context

NekoBox uses Kryo binary equality (ignoring `name`) to drive dedup. RIPDPI
needs an equivalent: a canonical byte serialization of the profile bean
followed by SHA-256 and compare-set. User-edited display names should
survive refresh; adversary-crafted collisions are out of scope (the
attacker already controls the subscription content).

## Acceptance criteria

- [ ] Canonical serializer produces a stable byte string for each protocol
    bean, ignoring `name` and any `finalAddress` runtime-only fields.
- [ ] Dedup hash column exists on `ProxyEntity` and is reindexed on every
    save.
- [ ] On subscription merge, incoming profiles hash-matching an existing
    profile inherit the incoming config but preserve the existing name
    and the user-edited `customOutboundJson` / `customConfigJson`.
- [ ] Unit tests cover: rename-only change (no-op), server-IP change
    (replace), UUID change (replace), new-profile (insert), missing-
    profile (delete).
- [ ] Dedup toggle on the group controls this behavior; off by default to
    match user expectation on first use.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — the `doUpdate()` merge pass (`existingByName`, `existingBean.equals(newBean)` calls). Read the delete/add/update/reorder reconciliation flow; port the structure, replace Kryo-equality with a canonical Protobuf encoding + SHA-256.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/AbstractBean.java` — `equals()` ignores `name` and `finalAddress`/`finalPort`. Mirror that invariant in the canonical serializer: exclude display name and transient resolved-address fields before hashing.

**Adapt:** The merge algorithm (preserve user-edited `customOutboundJson`/`customConfigJson` across refresh). **Skip:** Kryo-dependent equality — use stable Protobuf bytes + SHA-256 instead since RIPDPI does not ship Kryo.

## Links

- [[Epic - NekoBox subscription and profile import]]
- [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]
