---
title: Add sing-box JSON subscription parser
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

- [ ] #task Add sing-box JSON subscription parser #repo/RIPDPI #area/outbound #status/backlog 🔺

## Summary

Parse sing-box JSON subscriptions — both a bare `outbounds:` array and a
single-outbound config — into RIPDPI profile beans.

## Context

sing-box JSON is the canonical subscription format for the modern bypass
stack (Xray, sing-box, Clash.Meta upstream ecosystem). Per NekoBox's
`RawUpdater.parseJSON`: detects JSON via JSONTokener, then inspects top-
level keys. Outbound-array entries become profiles; non-shadowsocks/trojan/
hysteria entries that cannot be mapped to a native bean fall back to
`ConfigBean` (raw JSON fragment).

## Acceptance criteria

- [ ] Detect JSON via a permissive tokener; reject only on throw.
- [ ] Route on top-level shape: `outbounds:` array → iterate; single
    outbound object → wrap as one-element array; Hysteria1 config shape;
    Shadowsocks config shape.
- [ ] Map known outbound `type:` values to RIPDPI beans (VMess, VLESS,
    Trojan, Shadowsocks, Hysteria, Hysteria2, TUIC, WireGuard, AnyTLS,
    ShadowTLS, SSH).
- [ ] Unknown outbound types round-trip as `ConfigBean` holding the raw
    JSON fragment, consumable by the Rust engine via custom-config path.
- [ ] Malformed JSON surfaces as typed error with line/column pointer.
- [ ] Unit tests cover each mapping plus fall-through to `ConfigBean`.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — method `parseJSON()`. Detection: `JSONTokener(text).nextValue()` returns a `JSONObject` or `JSONArray`. Dispatch on top-level shape: `outbounds:` array (iterate), single outbound object (wrap), Hysteria1 single-config shape, Shadowsocks single-config shape, TrojanGo single-config shape.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt` — the reverse mapping (ProxyEntity → sing-box outbound JSON) is instructive for understanding which sing-box `type:` values map to which beans.

**Adapt:** The shape-detection dispatch, fall-through-to-ConfigBean for unknown types. **Skip:** sing-box `inbounds`, `route`, `dns`, `experimental` sections (we only want outbounds). Use `kotlinx.serialization` with a permissive JSON config (`ignoreUnknownKeys = true`, `isLenient = true`); NekoBox uses `org.json.JSONObject` which is slower and weaker-typed.

## Links

- [[Epic - NekoBox subscription and profile import]]
