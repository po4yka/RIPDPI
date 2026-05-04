---
title: Add WireGuard INI subscription parser
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

- [ ] #task Add WireGuard INI subscription parser #repo/RIPDPI #area/outbound #status/backlog ⏫

## Summary

Parse standard `.conf`-style WireGuard INI payloads (multi-peer supported)
into one WireGuard profile per peer.

## Context

Subscription providers sometimes distribute WireGuard nodes as raw INI,
including WARP-compatible layouts. Detection marker is `[Interface]`
presence. Multiple `[Peer]` sections produce multiple profiles sharing the
interface key material; surface them clearly in the populated group.

## Acceptance criteria

- [ ] Detect INI via `[Interface]` header presence.
- [ ] Parse `[Interface]` (PrivateKey, Address, DNS, MTU) and each `[Peer]`
    (PublicKey, AllowedIPs, Endpoint, PresharedKey, PersistentKeepalive).
- [ ] Produce one WireGuard profile per peer, sharing the interface
    keypair and distinguishing by peer endpoint in display name.
- [ ] Preserve `AllowedIPs` as per-profile routing hint even if the
    runtime currently ignores it; keep for future routing epic.
- [ ] Malformed INI surfaces a typed error; per-peer failures degrade to
    "skip and warn", not full subscription rejection.
- [ ] Unit tests cover: single-peer, multi-peer, WARP-style config, DNS
    field present and absent, IPv4-only and dual-stack AllowedIPs.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — method `parseWireGuard(text)`. Detection: `text.contains("[Interface]")`. Uses `org.ini4j.Ini` to parse.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/wireguard/` — the `WireGuardBean` field set that receives parsed values.

**amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — the AWG-extended INI parser is the definitive reference for Jc/Jmin/Jmax/S1-S4/H1-H4/I1-I5 key handling:

- `tunnel/src/main/java/org/amnezia/awg/config/Config.java` (`parse(InputStream)` starting line 50) — section dispatch on `[Interface]` / `[Peer]`.
- `tunnel/src/main/java/org/amnezia/awg/config/Interface.java:101-184` — the per-key `switch` that parses every AWG extension key. **Port this switch verbatim** for the [[Wire AmneziaWG into the subscription WireGuard-INI parser]] follow-on task.

**Adapt:** Detection marker, per-section header handling, per-peer profile emission. **Skip:** NekoBox's `ini4j` dependency if RIPDPI already has an INI parser; otherwise add it. Use `ini4j` 0.5.4 (same version NekoBox pins) for parity.

## Links

- [[Epic - NekoBox subscription and profile import]]
