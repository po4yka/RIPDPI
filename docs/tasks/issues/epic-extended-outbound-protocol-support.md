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
updated: 2026-04-24
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

Cover every outbound protocol type that realistic third-party subscriptions
still ship: Shadowsocks, HTTP(S), SOCKS5, VMess, Trojan, Trojan-Go, SSH,
AnyTLS, Mieru, and Hysteria v1. Today RIPDPI fronts the modern stack
(VLESS-Reality, Hysteria2, TUIC, WARP, ShadowTLS, Naive, MASQUE, xHTTP) but
cannot consume nodes published in these older or commodity formats, forcing
users to maintain a second app. The biggest gap is **Shadowsocks**, which
is the most common protocol across real-world bypass subscriptions; RIPDPI
currently only has SS as an inbound framing format, not a full outbound
client.

## Why now

Subscription import (blocking epic) is only useful if the protocols listed in
the subscription can be executed. VMess and Trojan are the most common in
Russian/Iranian/Chinese bypass scenes after VLESS-Reality; skipping them
cripples subscription adoption.

## Key decisions

- **Native Rust crates, mirroring existing pattern** (`ripdpi-vless`,
`ripdpi-hysteria2`, `ripdpi-tuic`, `ripdpi-shadowtls`). No external C/Go
binaries in the outbound path for these.
- **Protocol inclusion bar: must be present in realistic bypass
subscriptions.** The full matrix is Shadowsocks, HTTP(S), SOCKS5,
VMess, Trojan, Trojan-Go, SSH, AnyTLS, Mieru, Hysteria-v1.
**Tor is deliberately excluded** — RIPDPI already ships obfs4 and
Snowflake via the Lyrebird binary, which covers the Tor-bridge use
case without pulling in Tor's directory/consensus layer. SOCKS4/4a
are deliberately excluded as legacy with negligible presence.
- **SSH is included** because it remains a common relay for hobbyist
network-path compatibility setups, despite low share-count; the existing `ripdpi-
warp-core` noise primitives are unrelated — SSH needs its own crypto.
- **VMess is included but marked legacy.** New subscriptions should not rely
on it; we support decoding/consuming but do not surface it in the
new-profile UI beyond an "advanced / legacy" expander.
- **Hysteria v1 is included for transition,** but once subscriptions have
fully migrated to v2 the v1 crate should be removed, not left to rot.

## Scope

- **In scope:** Rust crates for Shadowsocks, HTTP(S), SOCKS5, VMess,
Trojan, Trojan-Go, SSH, AnyTLS, Mieru, Hysteria v1 outbounds; UI
editor screens; URI codec extension; integration into the existing
relay supervisor model; strategy-pack compatibility hints per
protocol.
- **Out of scope:** Tor (see exclusion rationale above), Brook, SOCKS4/4a,
other SagerNet-branded protocols; inbound server roles for any of these;
Shadowsocks plugins (simple-obfs, v2ray-plugin) — a follow-up epic if
real subscription samples demand them.

## Ship definition

- [ ] `ripdpi-shadowsocks`, `ripdpi-http-proxy`, `ripdpi-socks5-client`,
    `ripdpi-vmess`, `ripdpi-trojan`, `ripdpi-trojan-go`, `ripdpi-ssh`,
    `ripdpi-anytls`, `ripdpi-mieru`, and `ripdpi-hysteria-v1` crates
    exist, unit-tested against upstream reference test vectors.
- [ ] Each protocol has a profile-edit screen with schema-backed validation.
- [ ] Each protocol can be parsed from its standard URI scheme into a valid
    RIPDPI profile and round-tripped back to URI.
- [ ] Strategy-pack metadata includes per-protocol compatibility hints
    (e.g. Trojan inside xHTTP, SSH direct vs SSH-over-TLS).
- [ ] Relay supervisor can start and stop each protocol cleanly; shutdown
    joins bounded handler work (same invariant as existing protocols).
- [ ] Secrets (passwords, UUIDs, private keys) are redacted in logs,
    diagnostics, and crash reports, not only at export time.

## Child tasks

**Foundational (common in subscriptions)**
- [[Add Shadowsocks outbound client crate and profile editor]]
- [[Add HTTP and SOCKS5 outbound proxy clients]]

**Protocol long tail**
- [[Add VMess outbound client crate and profile editor]]
- [[Add Trojan outbound client crate and profile editor]]
- [[Add Trojan-Go outbound client crate and profile editor]]
- [[Add SSH outbound client crate and profile editor]]
- [[Add AnyTLS outbound client crate and profile editor]]
- [[Add Mieru outbound client crate and profile editor]]
- [[Add Hysteria v1 outbound client crate and profile editor]]

## Dependencies

- Unblocks: subscription-driven deployment in [[Epic - reference implementation subscription
and profile import]]; without these crates, VMess/Trojan/Hysteria-v1 nodes
in imported subscriptions cannot actually connect.

## Risks / open questions

- VMess AEAD vs legacy security variants: pick a supported matrix and reject
unsupported modes with typed errors, not silent downgrade.
- SSH channel multiplexing adds complexity; consider single-channel v1 before
committing to full multiplexing.
- Strategy-pack cross-product explodes with five new protocols; keep
per-protocol recommended arms tight.
- Hysteria v1 removal timeline needs a committed sunset date to avoid
long-tail maintenance.

## Links

- [[ripdpi-android]]
- [[Epic - Subscription and profile import]]
- Child issues: 9
