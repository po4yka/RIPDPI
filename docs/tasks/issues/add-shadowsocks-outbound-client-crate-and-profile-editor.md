---
title: Add Shadowsocks outbound client crate and profile editor
type: task
status: backlog
area: outbound
priority: critical
owner: unassigned
parent: epic-extended-outbound-protocol-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add Shadowsocks outbound client crate and profile editor #repo/RIPDPI #area/outbound #status/backlog 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-shadowsocks-outbound-client-crate-and-profile-editor`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-shadowsocks`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-shadowsocks/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a `ripdpi-shadowsocks` Rust crate implementing the full Shadowsocks
outbound client (AEAD-2022 + legacy AEAD ciphers) and a
`ShadowsocksProfileScreen` editor. Today RIPDPI only ships SS request-
parsing as an inbound framing format; there is no outbound client.

## Context

Shadowsocks is the most common protocol across third-party bypass
subscriptions in every target region. Without an outbound client, SS
entries in imported subscriptions cannot connect. Use AEAD-2022 ciphers
first (`2022-blake3-aes-256-gcm`, `2022-blake3-chacha20-poly1305`) and
the legacy AEAD family (`aes-256-gcm`, `chacha20-ietf-poly1305`) for
subscription compat. `simple-obfs` and `v2ray-plugin` are out of scope
for v1; they are plugin layers and belong to a later task.

## Acceptance criteria

- [ ] `ripdpi-shadowsocks` crate compiles standalone and inside the
    android-jni workspace.
- [ ] AEAD-2022 ciphers pass upstream test vectors (Shadowsocks-rust
    parity suite).
- [ ] Legacy AEAD ciphers (`aes-128-gcm`, `aes-256-gcm`,
    `chacha20-ietf-poly1305`) pass upstream test vectors.
- [ ] Stream ciphers (`rc4`, `aes-cfb`, `chacha20`, `salsa20`, etc.)
    are rejected with a typed error; never silently downgraded.
- [ ] TCP and UDP modes both supported.
- [ ] `ShadowsocksProfileScreen` validates server + port, password
    length, cipher picker with only supported ciphers.
- [ ] Password is stored via EncryptedFile; never plaintext in
    preferences, never surfaced in logs or exports.
- [ ] Subscription import path (Clash YAML + base64 URI list) routes
    SS entries to this crate.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/fmt/shadowsocks/ShadowsocksBean.java` — bean fields: `method`, `password`, `plugin`, `pluginOptions`. Port field set for RIPDPI's `ShadowsocksBean`.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/shadowsocks/ShadowsocksFmt.kt` — `ss://` URI parse (SIP002 format with base64 userinfo, plus legacy base64-whole-URI) and emit. **Port verbatim.**
- `app/src/main/java/io/nekohasekai/sagernet/ui/profile/ShadowsocksSettingsActivity.kt` — the editor's validation rules for method/password/plugin. Reference only; RIPDPI editor will be Compose, not PreferenceFragment.

**Outbound engine (NOT from NekoBox):**
- [`shadowsocks-rust`](https://github.com/shadowsocks/shadowsocks-rust) — pure-Rust reference implementation. Shadowsocks-rust's `shadowsocks-crypto` crate has the AEAD-2022 and legacy AEAD ciphers. Consume as a dependency or vendored fork.
- NekoBox's outbound is sing-box's Go implementation; **do not port that**.

**Adapt:** Bean fields, URI codec, validation rules. **Skip:** sing-box Go outbound, any "plugin" external-process path (simple-obfs / v2ray-plugin are out of scope for v1).

## Links

- [[Epic - Extended outbound protocol support]]
- [[Epic - NekoBox subscription and profile import]]
