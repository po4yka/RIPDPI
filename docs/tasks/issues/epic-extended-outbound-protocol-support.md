---
title: Epic - Extended outbound protocol support
type: epic
status: backlog
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-28
---

- [ ] #task Epic - Extended outbound protocol support #repo/RIPDPI #area/epic #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-extended-outbound-protocol-support`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Cover the remaining outbound protocol types that realistic third-party subscriptions still ship. Current source already has first-class Shadowsocks, Trojan, and AnyTLS support in the native relay stack and import paths; the open backlog is now VMess, Trojan-Go, SSH, Mieru, Hysteria v1, and any future decision to add generic HTTP(S)/SOCKS5 outbound profiles.

## Why now

Subscription import is only useful if imported protocols can execute. VMess remains legacy but common in older feeds; Trojan-Go, Mieru, SSH, and Hysteria v1 are lower-volume compatibility work. Trojan itself has landed and should not be re-added through this epic.

## Key decisions

- **Native Rust crates, mirroring existing pattern** (`ripdpi-vless`, `ripdpi-hysteria2`, `ripdpi-tuic`, `ripdpi-shadowtls`). No external C/Go binaries in the outbound path for these.
- **Protocol inclusion bar: must be present in realistic bypass subscriptions.** The remaining matrix is VMess, Trojan-Go, SSH, Mieru, Hysteria-v1, and possibly generic HTTP(S)/SOCKS5 outbound profiles if subscription samples justify them. **Tor is deliberately excluded** from this outbound-compatibility epic because it is a separate anonymity backend decision.
- **SSH is included** because it remains a common relay for hobbyist network-path compatibility setups, despite low share-count; the existing `ripdpi- warp-core` noise primitives are unrelated — SSH needs its own crypto.
- **VMess is included but marked legacy.** New subscriptions should not rely on it; we support decoding/consuming but do not surface it in the new-profile UI beyond an "advanced / legacy" expander.
- **Hysteria v1 is included for transition,** but once subscriptions have fully migrated to v2 the v1 crate should be removed, not left to rot.

## Scope

- **Already landed:** Shadowsocks, Trojan, and AnyTLS have native relay support and import paths in current source.
- **In scope:** Rust crates/profile support for VMess, Trojan-Go, SSH, Mieru, Hysteria v1, and any approved generic HTTP(S)/SOCKS5 outbound profiles; UI editor screens; URI codec extension where a real scheme exists; integration into the existing relay supervisor model; strategy-pack compatibility hints per protocol.
- **Out of scope:** Tor (see exclusion rationale above), Brook, SOCKS4/4a, other SagerNet-branded protocols; inbound server roles for any of these; Shadowsocks plugins (simple-obfs, v2ray-plugin) — a follow-up epic if real subscription samples demand them.

## Ship definition

- [ ] Remaining protocol crates/profile support exist and are unit-tested against upstream reference test vectors.
- [ ] Each protocol has a profile-edit screen with schema-backed validation.
- [ ] Each protocol can be parsed from its standard URI scheme into a valid RIPDPI profile and round-tripped back to URI.
- [ ] Strategy-pack metadata includes per-protocol compatibility hints (e.g. Trojan inside xHTTP, SSH direct vs SSH-over-TLS).
- [ ] Relay supervisor can start and stop each protocol cleanly; shutdown joins bounded handler work (same invariant as existing protocols).
- [ ] Secrets (passwords, UUIDs, private keys) are redacted in logs, diagnostics, and crash reports, not only at export time.

## Child tasks

- [[Add VMess outbound client crate and profile editor]]
- [[Add Trojan-Go outbound client crate and profile editor]]
- [[Add SSH outbound client crate and profile editor]]
- [[Finish AnyTLS profile editor and compatibility gaps]]
- [[Add Mieru outbound client crate and profile editor]]
- [[Add Hysteria v1 outbound client crate and profile editor]]

## Dependencies

- Unblocks: subscription-driven deployment in [[Epic - reference implementation subscription and profile import]]; without these crates, VMess/Trojan/Hysteria-v1 nodes in imported subscriptions cannot actually connect.

## Risks / open questions

- VMess AEAD vs legacy security variants: pick a supported matrix and reject unsupported modes with typed errors, not silent downgrade.
- SSH channel multiplexing adds complexity; consider single-channel v1 before committing to full multiplexing.
- Strategy-pack cross-product explodes with five new protocols; keep per-protocol recommended arms tight.
- Hysteria v1 removal timeline needs a committed sunset date to avoid long-tail maintenance.

## Links

- [[ripdpi-android]]
- [[Epic - Subscription and profile import]]
- Child issues: 6
