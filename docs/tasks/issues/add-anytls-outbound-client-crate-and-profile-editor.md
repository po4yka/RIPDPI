---
title: Add AnyTLS outbound client crate and profile editor
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-extended-outbound-protocol-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add AnyTLS outbound client crate and profile editor #repo/RIPDPI #area/outbound #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-anytls-outbound-client-crate-and-profile-editor`
- **Verify:** `just test-rust`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-anytls/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a `ripdpi-anytls` Rust crate implementing the AnyTLS client and a
`AnyTLSProfileScreen` editor. AnyTLS is the newer sing-anytls protocol
designed to reduce TLS-in-TLS detection vs ShadowTLS.

## Context

Upstream reference: `anytls/sing-anytls`. The protocol coexists with
ShadowTLS on RIPDPI's roadmap because subscription providers are split
between the two. Reuse the existing ShadowTLS TLS session machinery where
shape overlaps.

## Acceptance criteria

- [ ] `ripdpi-anytls` crate passes upstream reference handshake and
    session-framing test vectors.
- [ ] Fallback-SNI and fallback-server behavior matches upstream spec.
- [ ] `AnyTLSProfileScreen` validates password length, server + port,
    and server-name (SNI).
- [ ] Integrate with relay supervisor lifecycle; shutdown joins bounded
    handler work.
- [ ] Strategy-pack metadata advertises AnyTLS compat hints, especially
    around QUIC-heavy neighborhoods.
- [ ] Password is redacted in all diagnostic surfaces.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSBean.java` — bean fields: `password`, `sni`, `alpn`, `allowInsecure`, `idleSessionCheckInterval`, `idleSessionTimeout`, `minIdleSession`.
- `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSFmt.kt` — `anytls://` URI codec.
- `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSSettingsActivity.kt` — editor.

**Outbound engine (NOT from reference implementation):** upstream [`anytls/sing-anytls`](https://github.com/anytls/sing-anytls) (Go). No Rust port; either port to Rust or consume the spec directly. The handshake is small — HMAC-SHA1-based with session-ID camouflage.

**Adapt:** Bean fields, URI codec, idle-session fields (AnyTLS-specific). **Skip:** sing-box integration layer from reference implementation.

## Links

- [[Epic - Extended outbound protocol support]]
