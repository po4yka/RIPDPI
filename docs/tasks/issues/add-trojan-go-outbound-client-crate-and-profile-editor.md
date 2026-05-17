---
title: Add Trojan-Go outbound client crate and profile editor
type: task
status: backlog
area: outbound
priority: low
owner: unassigned
parent: epic-extended-outbound-protocol-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Add Trojan-Go outbound client crate and profile editor #repo/RIPDPI #area/outbound #status/backlog 🔽

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-trojan-go-outbound-client-crate-and-profile-editor`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-trojan-go`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-trojan-go/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a `ripdpi-trojan-go` Rust crate for Trojan-Go subscriptions. Trojan-Go extends Trojan with WebSocket / mux / plugin framing; it is declining in usage but still present in some subscription mixes.

## Context

Trojan-Go and Trojan share the password-hash handshake but differ in transport framing. Keeping them as separate crates avoids mixing two protocols in one and lets Trojan-Go be sunset independently later. Plugin framing (simple-obfs plugin) is NOT part of v1; only the WebSocket-over-TLS + mux transport is.

## Acceptance criteria

- [ ] `ripdpi-trojan-go` crate compiles and passes upstream v0.x test vectors for handshake and WebSocket framing.
- [ ] Mux support: SMUX v1 mode.
- [ ] Shadowsocks-AEAD inner encryption option supported.
- [ ] `TrojanGoProfileScreen` validates SNI, password, WS path, optional SS cipher for inner encryption.
- [ ] Profile is flagged as legacy in UI lists.
- [ ] Sunset date committed in the crate top-of-file comment.
- [ ] Password redacted in all diagnostic surfaces.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/fmt/trojan_go/TrojanGoBean.java` — full extended field set: `encryption` (shadowsocks-AEAD inner cipher), `plugin`, `pluginOpts`, `path`, `host`, `type` (ws/h2), `mux`.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/trojan_go/TrojanGoFmt.kt` — `trojan-go://` URI codec.
- `app/src/main/java/io/nekohasekai/sagernet/ui/profile/TrojanGoSettingsActivity.kt` — editor.

**Outbound engine (NOT from reference implementation):** upstream [`p4gefau1t/trojan-go`](https://github.com/p4gefau1t/trojan-go) (Go, archived) is the spec reference. No Rust port exists; write one or accept the crate stays legacy-only and is removed per the sunset commitment in the epic.

**Adapt:** Bean fields, URI codec. **Skip:** Reference implementation's external-process path via `trojan-go-plugin` APK (RIPDPI architecture is Rust-only, no external binaries via plugin ecosystem).

## Links

- [[Epic - Extended outbound protocol support]]
- [[Add Trojan outbound client crate and profile editor]]
