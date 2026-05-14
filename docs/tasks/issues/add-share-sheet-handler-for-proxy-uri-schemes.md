---
title: Add share-sheet handler for proxy URI schemes
type: task
status: backlog
area: ui
priority: high
owner: unassigned
parent: epic-qr-code-and-clipboard-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add share-sheet handler for proxy URI schemes #repo/RIPDPI #area/ui #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-share-sheet-handler-for-proxy-uri-schemes`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Register intent filters so RIPDPI appears in the Android share sheet (and
as a URL opener) for `vless://`, `vmess://`, `trojan://`, `ss://`,
`hysteria://`, `hysteria2://`, `tuic://`, `anytls://`, `ssh://`, and
grouped NekoBox `sn://` schemes.

## Context

Today RIPDPI only handles `ripdpi://`. Extending the filters lets users
tap a share link in Telegram or a browser and land directly in the
profile-edit flow. No subscription schemes are claimed here — that is
handled by URL import inside the subscription epic.

## Acceptance criteria

- [ ] `MainActivity` or a dedicated entry Activity declares intent filters
    for each listed scheme.
- [ ] The handler dispatches to the shared URI codec and navigates to
    profile-edit with populated state.
- [ ] Multiple filter priority avoids claiming HTTPS — browser ordering
    for `https://` stays untouched.
- [ ] Unknown sub-schemes fall through to a typed "unsupported scheme"
    error, not a crash.
- [ ] Instrumented test covers at least one representative URI per
    scheme.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/AndroidManifest.xml` — the `MainActivity` intent-filter list declares each scheme (`sn://`, `ss://`, `ssr://`, `vmess://`, `trojan://`, `trojan-go://`, `naive+https://`, `naive+quic://`, `hysteria://`, `socks://`, `socks4://`, `socksa://`, `sock5://`, plus `clash://install-config` subscription scheme). Port the filter list shape.
- `app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt` — `onNewIntent()` routes by scheme to parse-and-open-editor vs parse-and-create-subscription paths.
- Per-protocol URI codecs under `app/src/main/java/io/nekohasekai/sagernet/fmt/` — **the canonical source of truth for each scheme**:
- `shadowsocks/ShadowsocksFmt.kt` — `ss://` parse + emit (SIP002 format)
- `trojan/TrojanFmt.kt` — `trojan://`
- `v2ray/V2RayFmt.kt` — `vmess://` (JSON-base64 and standard), `vless://`, also `trojan://` variant
- `hysteria/HysteriaFmt.kt` — `hysteria://`, `hysteria2://`, `hy2://`
- `tuic/TuicFmt.kt` — `tuic://`
- `socks/SOCKSFmt.kt` — `socks5://`, `socks://`, `sock5://`, `socks4://`, `socksa://`
- `http/HttpFmt.kt` — `http://`, `https://` (as proxy URIs)
- `naive/NaiveFmt.kt` — `naive+https://`, `naive+quic://`
- `trojan_go/TrojanGoFmt.kt` — `trojan-go://`
- `moe/matsuri/nb4a/proxy/anytls/AnyTLSFmt.kt` — `anytls://`
- `moe/matsuri/nb4a/proxy/shadowtls/ShadowTLSFmt.kt` — `shadowtls://` (non-standard)

**Adapt:** Full intent-filter manifest block, per-scheme dispatch in activity, full URI codec set. **Skip:** `sn://` universal link.

## Links

- [[Epic - QR code and clipboard profile import]]


## remove-cloudflare-from-critical
