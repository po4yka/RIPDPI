---
title: Add Trojan outbound client crate and profile editor
type: task
status: backlog
area: outbound
priority: high
owner: unassigned
parent: epic-extended-outbound-protocol-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add Trojan outbound client crate and profile editor #repo/RIPDPI #area/outbound #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-trojan-outbound-client-crate-and-profile-editor`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-trojan`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-trojan/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a `ripdpi-trojan` Rust crate implementing the Trojan client over TLS
plus a `TrojanProfileScreen` editor. Trojan is still common in real-world
bypass subscriptions that have not migrated to VLESS-Reality.

## Context

Wire format is straightforward (SHA-224(password) + command + target).
TLS transport reuses the existing transport crate. Keep the client narrowly
focused: Trojan only, no Trojan-Go extensions — those belong to a separate
crate if ever added.

## Acceptance criteria

- [ ] `ripdpi-trojan` crate passes upstream reference test vectors for
    handshake and target framing.
- [ ] TCP and UDP ASSOCIATE modes both supported.
- [ ] TLS layer allows pluggable SNI, ALPN, and certificate verification
    toggle (insecure mode behind a debug-only flag).
- [ ] `TrojanProfileScreen` validates SNI hostname, password length, ALPN
    list.
- [ ] WebSocket and gRPC transports over TLS are supported (reuse
    existing transports).
- [ ] Password is SHA-224 hashed in-memory; plaintext never written to
    disk. Redacted in all diagnostic surfaces.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/fmt/trojan/TrojanBean.java` — bean fields: `password`, `sni`, `alpn`, plus transport (reuses StandardV2RayBean pattern).
- `app/src/main/java/io/nekohasekai/sagernet/fmt/trojan/TrojanFmt.kt` — `trojan://` URI parse + emit.
- `app/src/main/java/io/nekohasekai/sagernet/ui/profile/TrojanSettingsActivity.kt` — editor validation rules.

**Outbound engine (NOT from NekoBox):** Trojan wire format is simple (SHA-224(password) + command byte + target address + CRLF). Hand-roll in Rust; ~100 lines core handshake + reuse RIPDPI's existing TLS + transport layers.

**Adapt:** Bean fields, URI codec, SNI/ALPN validation. **Skip:** Trojan-Go extensions (separate crate, [[Add Trojan-Go outbound client crate and profile editor]]).

## Links

- [[Epic - Extended outbound protocol support]]
