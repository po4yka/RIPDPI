---
title: Wire AmneziaWG into the subscription WireGuard-INI parser
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-amneziawg-outbound-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Wire AmneziaWG into the subscription WireGuard-INI parser #repo/RIPDPI #area/outbound #status/backlog 🔼

## Summary

Extend the WireGuard-INI subscription parser so a subscription
containing an AWG-flavored `[Interface]` block produces an
`AmneziaWGBean`, not a vanilla `WireGuardBean`.

## Context

Depends on the `AmneziaWGBean` + parser extension task landing first.
Detection is by presence of any AWG key in the `[Interface]` block;
zero AWG keys → vanilla WG bean; any AWG key → AWG bean. Multi-peer
INI files follow the same per-peer semantics as the existing parser.
No new subscription format is added.

## Acceptance criteria

- [ ] `RawUpdater` (or equivalent) WireGuard-INI parser routes
    `[Interface]` blocks to the right bean type based on AWG-key
    presence.
- [ ] Multi-peer INI files work: interface-scope AWG fields apply to
    all peer profiles derived from the file.
- [ ] Mixed subscription: an INI file with both an AWG interface and
    a vanilla interface (unusual but possible) produces the right
    bean for each.
- [ ] Subscription refresh preserves user-edited override fields on
    AWG beans just as on vanilla WG beans.
- [ ] Unit tests cover: AWG INI, vanilla INI, AWG with partial fields,
    malformed AWG fields (warning, skip line, continue).

## Source references

**amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`):

- `tunnel/src/main/java/org/amnezia/awg/config/Interface.java:101-184` — the INI-key `switch` is already the canonical implementation of routing AWG keys to the right fields. Shared with the `.conf` parser task; this task plugs the same shape into the subscription path.
- `tunnel/src/main/java/org/amnezia/awg/config/Config.java` — `parse(InputStream)` — section dispatch already ignores whitespace-surrounded keys and is tolerant of blank lines. Port directly.

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — `parseWireGuard()` — the existing subscription WG-INI parser. This task extends it with the AWG-key detection branch: if `[Interface]` contains any AWG key, emit an `AmneziaWGBean`; else emit `WireGuardBean`.

**Adapt:** Detection logic (any of `jc`/`jmin`/`jmax`/`s1..s4`/`h1..h4`/`i1..i5` → AWG bean), graceful degradation if AWG fields are malformed. **Skip:** nothing meaningful — this is a small targeted extension.

## Links

- [[Epic - AmneziaWG outbound support]]
- [[Add WireGuard INI subscription parser]]
- [[Add AmneziaWG Kotlin config model and dot-conf parser extensions]]


## boot-autostart-and-session
