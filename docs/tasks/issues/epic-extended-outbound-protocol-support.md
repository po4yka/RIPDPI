---
title: Epic - Extended outbound protocol support
type: epic
status: doing
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-06-01
---

> **2026-06-01 — scope reduced per [ADR 0004](../../adr/0004-protocol-support-policy.md).** VMess, Trojan-Go, and Hysteria v1 are **dropped from this epic and removed from the codebase** — they were never-completed stubs that carried no traffic, and RIPDPI maintains support only for current/actual protocols. The remaining open backlog is **SSH** and **Mieru** only (not-yet-implemented compatibility work, explicitly *not* legacy). Their child tasks are deleted.

## Goal

Cover the remaining outbound protocol types that realistic third-party subscriptions still ship. Current source already has first-class Shadowsocks, Trojan, and AnyTLS support in the native relay stack and import paths; the open backlog is now **SSH and Mieru** (and any future decision to add generic HTTP(S)/SOCKS5 outbound profiles). VMess, Trojan-Go, and Hysteria v1 were evaluated and removed — see [ADR 0004](../../adr/0004-protocol-support-policy.md).

## Why now

Subscription import is only useful if imported protocols can execute. SSH and Mieru are lower-volume compatibility work. Trojan itself has landed and should not be re-added through this epic.

## Key decisions

- **Native Rust crates, mirroring existing pattern** (`ripdpi-vless`, `ripdpi-hysteria2`, `ripdpi-tuic`, `ripdpi-shadowtls`). No external C/Go binaries in the outbound path for these.
- **Protocol inclusion bar: must be present in realistic bypass subscriptions.** The remaining matrix is SSH, Mieru, and possibly generic HTTP(S)/SOCKS5 outbound profiles if subscription samples justify them. **Tor is deliberately excluded** from this outbound-compatibility epic because it is a separate anonymity backend decision.
- **SSH is included** because it remains a common relay for hobbyist network-path compatibility setups, despite low share-count; the existing `ripdpi-warp-core` noise primitives are unrelated — SSH needs its own crypto.
- **VMess, Trojan-Go, and Hysteria v1 are removed, not stubbed** — see [ADR 0004](../../adr/0004-protocol-support-policy.md). A protocol that becomes legacy and unused is deleted rather than left to rot; the inclusion bar is "current and maintained upstream."

## Scope

- **Already landed:** Shadowsocks, Trojan, and AnyTLS have native relay support and import paths in current source.
- **In scope:** Rust crates/profile support for SSH and Mieru, and any approved generic HTTP(S)/SOCKS5 outbound profiles; UI editor screens; URI codec extension where a real scheme exists; integration into the existing relay supervisor model; strategy-pack compatibility hints per protocol.
- **Removed (was in scope):** VMess, Trojan-Go, Hysteria v1 — deleted from code and docs per [ADR 0004](../../adr/0004-protocol-support-policy.md).
- **Out of scope:** Tor (see exclusion rationale above), Brook, SOCKS4/4a, other SagerNet-branded protocols; inbound server roles for any of these; Shadowsocks plugins (simple-obfs, v2ray-plugin) — a follow-up epic if real subscription samples demand them.

## Ship definition

- [ ] Remaining protocol crates/profile support exist and are unit-tested against upstream reference test vectors.
- [ ] Each protocol has a profile-edit screen with schema-backed validation.
- [ ] Each protocol can be parsed from its standard URI scheme into a valid RIPDPI profile and round-tripped back to URI.
- [ ] Strategy-pack metadata includes per-protocol compatibility hints (e.g. Trojan inside xHTTP, SSH direct vs SSH-over-TLS).
- [ ] Relay supervisor can start and stop each protocol cleanly; shutdown joins bounded handler work (same invariant as existing protocols).
- [ ] Secrets (passwords, UUIDs, private keys) are redacted in logs, diagnostics, and crash reports, not only at export time.

## Child tasks

- [[Add SSH outbound client crate and profile editor]]
- [[Finish AnyTLS profile editor and compatibility gaps]]
- [[Add Mieru outbound client crate and profile editor]]

(Removed per [ADR 0004](../../adr/0004-protocol-support-policy.md): VMess, Trojan-Go, Hysteria v1 child tasks and crates.)

## Dependencies

- Unblocks: subscription-driven deployment in Epic - reference implementation subscription and profile import. Nodes naming a removed protocol (VMess/Trojan-Go/Hysteria v1) in an imported subscription are skipped, not connected.

## Risks / open questions

- SSH channel multiplexing adds complexity; consider single-channel v1 before committing to full multiplexing.
- Strategy-pack cross-product growth; keep per-protocol recommended arms tight.

## Links

- [[ripdpi-android]]
- Epic - Subscription and profile import
- [ADR 0004: Protocol Support Policy](../../adr/0004-protocol-support-policy.md)
- Child issues: 3
