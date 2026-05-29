---
title: Add runetfreedom russia-v2ray-rules geoip preset
type: task
status: done
area: routing
priority: medium
owner: unassigned
parent: epic-advanced-routing-rules-and-geoip-enforcement
blocks: []
blocked_by: []
created: 2026-05-29
updated: 2026-05-29
---

- [x] #task Add runetfreedom russia-v2ray-rules geoip preset #repo/RIPDPI #area/routing #status/done 🔼

## Summary

Add `runetfreedom/russia-v2ray-rules-dat` as a built-in geoip/geosite asset provider preset alongside the existing four, since it is the de-facto RU bypass ruleset shipped by comparable clients.

## Context

[[Add configurable asset provider picker with four presets]] ships SagerNet, soffchen, Chocolate4U (Iran), and L11R antizapret. The RU threat model — central to this project and its sibling deploy/Meridian repos — is best served by `runetfreedom/russia-v2ray-rules-dat`, which xivpn bundles directly and which is more current than antizapret for v2ray/xray `geoip`/`geosite` tags. This task augments the presets task; it does not replace antizapret.

## Acceptance criteria

- [ ] `runetfreedom/russia-v2ray-rules-dat` is selectable as a built-in provider preset (geoip.db + geosite.db).
- [ ] Update is user-triggered (not silent background refresh), consistent with the existing presets task.
- [ ] Asset download honors `.claude/rules/vpnservice-protect-invariant.md` (outbound socket protected) and verifies integrity (hash/signature) before the asset goes live; corrupt/blocked downloads fail closed.
- [ ] "Asset is N days old" staleness is surfaced passively without nagging (matches the epic's UX note).
- [ ] The binary format is the SagerNet-compatible format the runtime loader already consumes (per the epic's geosite format decision); document any divergence.
- [ ] Provider URL + integrity pin are documented; consider an `upstream-spec-watch`-style refresh entry.

## Source references

**Reference (xivpn):** bundles `runetfreedom/russia-v2ray-rules-dat` and exposes geo-asset management (`GeoAssetsActivity`). Adopt the source/ruleset choice; do not copy code.

**Adapt:** the existing four-preset picker mechanism — add a fifth entry.

**Invent:** the integrity-pin + staleness surfacing for this specific source if not already generic.

## Links

- [[Epic - Advanced routing rules and geoip enforcement]]
- [[Add configurable asset provider picker with four presets]]
