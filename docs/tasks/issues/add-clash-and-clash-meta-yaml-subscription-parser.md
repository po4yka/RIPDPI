---
title: Add Clash and Clash.Meta YAML subscription parser
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

- [ ] #task Add Clash and Clash.Meta YAML subscription parser #repo/RIPDPI #area/outbound #status/backlog 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-clash-and-clash-meta-yaml-subscription-parser`
- **Verify:** `just test-module core:data:runtime-state`
- **Scope (only modify these + this file + the ledger):** `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Parse `proxies:` arrays from Clash and Clash.Meta YAML subscriptions into
RIPDPI profile beans.

## Context

Clash YAML is the most common subscription format in Chinese and Iranian
bypass ecosystems. Clash.Meta adds reality-opts, smux, and ech-opts on top.
NekoBox's `RawUpdater.kt` handles: socks5, http, ss (with obfs and v2ray-
plugin), vmess, vless (with reality-opts), trojan, anytls, hysteria,
hysteria2, tuic. Routing rules in the YAML are ignored — only node lists.

## Acceptance criteria

- [ ] Detect Clash YAML by presence of `proxies:` top-level key.
- [ ] Map Clash proxy types to RIPDPI profile beans for: socks5, http, ss,
    vmess, vless (with reality-opts, smux), trojan (with ech-opts),
    anytls, hysteria, hysteria2, tuic.
- [ ] Unknown fields are ignored, not hard-errored.
- [ ] Parser is streaming (SnakeYAML event-based) to handle 500+ node
    payloads without loading the whole document into memory.
- [ ] Parse failures surface as typed `SubscriptionParseError` with the
    failing node index, not a fatal stack trace.
- [ ] Unit tests cover a realistic sample bank for each listed protocol,
    plus malformed/partial inputs.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — method `parseRaw(text: String)`. The Clash branch is guarded by `text.contains("proxies:")`. Inside, every `proxies:` array entry is dispatched by `type` (`ss`, `vmess`, `vless`, `trojan`, `anytls`, `hysteria`, `hysteria2`, `tuic`, `socks5`, `http`). Port this switch verbatim; replace each branch's bean construction with the RIPDPI equivalent.
- Per-protocol Clash field mappings: same file, inline within each branch. Handle known quirks:
- `reality-opts` (public-key, short-id, spider-x) → VLESS-Reality fields
- `smux` (v1/v2, max-streams, max-connections) → mux composition (blocked on [[Epic - Composable transport layer parity]])
- `ech-opts` → ECH fields (RIPDPI already has these)
- `ws-opts` (path, headers, early-data) → WebSocket transport ([[Generalize WebSocket transport for outbound composition]])

**Adapt:** The detection string, switch dispatch, per-field mapping. **Skip:** Clash routing rules (`rules:`, `proxy-groups:` blocks) — NekoBox ignores them too. Use `snakeyaml-engine` (Kotlin-friendly) or event-based `snakeyaml` for streaming; NekoBox uses TypeDescription-driven SnakeYAML which is heavier than needed.

## Links

- [[Epic - NekoBox subscription and profile import]]
- [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]
