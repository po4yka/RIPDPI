---
title: Add VMess outbound client crate and profile editor
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-extended-outbound-protocol-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Add VMess outbound client crate and profile editor #repo/RIPDPI #area/outbound #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-vmess-outbound-client-crate-and-profile-editor`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-vmess`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-vmess/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a `ripdpi-vmess` Rust crate implementing VMess client outbound (AEAD
only, legacy security variants rejected with typed error) plus a
`VMessProfileScreen` editor.

## Context

VMess is legacy but widely present in older subscriptions. Supporting the
AEAD variant is sufficient for realistic traffic. Legacy `security: auto`
and MD5-based auth are explicitly unsupported. VMess transports (tcp, ws,
h2, grpc) reuse existing transport crates where possible.

## Acceptance criteria

- [ ] `ripdpi-vmess` crate compiles standalone and as part of the
    android-jni workspace.
- [ ] AEAD (`aes-128-gcm`, `chacha20-poly1305`) ciphers pass reference
    test vectors.
- [ ] Legacy security values (`auto`, `none`, `md5`) are rejected with
    typed error messages surfaced to the user.
- [ ] Transport matrix: tcp, ws, h2, grpc — all supported via shared
    transport layer.
- [ ] Profile editor enforces schema validation: UUID v4, port range,
    alterId=0 only, security whitelist.
- [ ] Profile is flagged "legacy" in lists so new users know it is not
    the recommended path.
- [ ] Secrets (UUID) are redacted in all logs, diagnostics, and exports.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/fmt/v2ray/VMessBean.java` (+ sibling `VLESSBean.java` via `alterId=-1`) — full field set: `uuid`, `alterId`, `encryption`, `security`, `packetEncoding`, `experiments`, plus transport fields (`type`, `host`, `path`, `headerType`, `mKcpSeed`, `quicSecurity`, `quicKey`, `grpcMode`, `grpcServiceName`, `wsMaxEarlyData`, `earlyDataHeaderName`, `reality*` etc.).
- `app/src/main/java/io/nekohasekai/sagernet/fmt/v2ray/V2RayFmt.kt` — `vmess://` parse (both base64-JSON and standard URI form) + emit. **Port verbatim.**
- `app/src/main/java/io/nekohasekai/sagernet/ui/profile/VMessSettingsActivity.kt` — validation rules.

**Outbound engine (NOT from reference implementation):** no mature pure-Rust VMess client exists; most likely path is to port the wire-format pieces from `xray-core` (Go) or `v2fly-core`. Evaluate [`v2ray-rust`](https://github.com/Qv2ray/v2ray-rust) as a starting point; it's unmaintained but has AEAD implementations.

**Adapt:** Bean fields, URI codec (both forms), legacy-cipher rejection policy. **Skip:** AlterID != 0 (deprecated); `aid` handling for legacy security variants; the reference implementation editor UI (build Compose instead).

## Links

- [[Epic - Extended outbound protocol support]]


## fail-closed-android-vpn
