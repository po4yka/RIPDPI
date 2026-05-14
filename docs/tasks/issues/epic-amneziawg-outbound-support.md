---
title: Epic - AmneziaWG outbound support
type: epic
status: backlog
area: epic
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Epic - AmneziaWG outbound support #repo/RIPDPI #area/epic #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-amneziawg-outbound-support`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** `add-proxygroup-and-subscription-entities-to-ripdpi-data-layer`, `add-wireguard-ini-subscription-parser`
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Add AmneziaWG (a DPI-resistant WireGuard fork) as a first-class outbound
in RIPDPI so users with AmneziaWG-protected endpoints do not need a
second app. AmneziaWG is widely deployed in Russian bypass infrastructure
as an alternative to vanilla WireGuard, which TSPU now fingerprints
trivially.

## Why now

RIPDPI already has a mature WireGuard stack via `ripdpi-warp-core`
(boringtun + smoltcp) for WARP. AmneziaWG adds a small set of handshake
modifications on top of the WireGuard wire protocol; adding it is cheap
relative to the user population gained. The feature-parity audit against
NekoBox did not surface this (NekoBox doesn't support AWG either) — it
is adjacent-scope expansion, not strict parity.

## Key decisions

- **Fork boringtun into `ripdpi-amneziawg-core`**, do not wrap the
Go `amneziawg-go`. RIPDPI is Rust-first; adding Go would regress the
architecture. The AWG handshake deltas are small enough to port.
- **Obfuscation params are server-coordinated.** Client `Jc/Jmin/Jmax/
S1..S4/H1..H4/I1..I5` must match the server's; no auto-tuning, no
strategy-learner variation of these params. Surface them as fixed
config fields only.
- **Config format:** extend the WireGuard `.conf` INI parser to
recognize the AWG keys as optional `[Interface]` fields. An `.conf`
without any AWG key parses as vanilla WireGuard; with any AWG key
it routes to the AWG outbound crate.
- **Backward compatibility:** client binary can roam between vanilla
and AWG servers without user intervention — profile type is inferred
from the config, not a toggle.
- **URI codec:** use `amneziawg://` scheme for single-profile sharing
rather than overloading `wireguard://` with AWG query params, to
keep round-trip semantics clean.
- **Out of scope:** kernel-module path (rooted devices); server-side
AWG role; migration/upgrade tools between WG and AWG configs.

## Scope

- **In scope:** `ripdpi-amneziawg-core` Rust crate forked from
boringtun with AWG handshake modifications (junk packets, header
substitution, size padding, AWG 2.0 I1–I5 intervals); Kotlin config
model + parser extension; profile editor; URI codec; subscription-
import routing; strategy-pack compatibility hint.
- **Out of scope:** rooted path via amneziawg kernel module;
AmneziaWG server mode; auto-tuning obfuscation params; an
"AmneziaWG vs WireGuard" migration assistant.

## Ship definition

- [ ] `ripdpi-amneziawg-core` crate with reference test vectors from
    amneziawg-go; all four packet types (initiation, response,
    cookie-reply, transport) support H1–H4 header substitution and
    S1–S4 size padding.
- [ ] Jc/Jmin/Jmax junk packet generation in the handshake prelude is
    observable on the wire (packet capture shows N random packets of
    size in [Jmin, Jmax] before the real initiation).
- [ ] AWG 2.0 I1–I5 special junk intervals land with the core work;
    not deferred.
- [ ] Kotlin `.conf` parser accepts both vanilla WG configs (no AWG
    keys) and AWG configs (any AWG key present); round-trip through
    import → edit → save preserves all fields.
- [ ] Profile editor exposes every AWG obfuscation field; all are
    free-text validated and surfaced inline (not hidden behind
    "Advanced").
- [ ] `amneziawg://` URI codec exports and imports profiles with full
    field set.
- [ ] Subscription import path: an INI-format subscription containing
    an AWG-flavored `[Interface]` block produces an AWG profile,
    not a vanilla WG profile.
- [ ] Strategy-pack metadata flags AWG profiles as "server-coordinated
    fixed config" so the strategy learner does not vary their
    obfuscation params.
- [ ] Secrets (private key, preshared key) redacted in all diagnostic
    surfaces and exports.

## Child tasks

**Rust core**
- [[Fork boringtun and add AmneziaWG handshake obfuscation]]

**Kotlin config + UI**
- [[Add AmneziaWG Kotlin config model and dot-conf parser extensions]]
- [[Add AmneziaWG profile editor screen with obfuscation fields]]
- [[Add amneziawg URI codec for profile share and import]]

**Integrations**
- [[Wire AmneziaWG into the subscription WireGuard-INI parser]]
- [[Add strategy-pack compatibility hints for AmneziaWG servers]]

## Dependencies

- Depends on: [[Add WireGuard INI subscription parser]] — the
subscription integration task extends the same parser.
- Depends on: [[Add ProxyGroup and Subscription entities to RIPDPI data
layer]] — AWG profiles live in the same ProxyEntity store.
- Feeds: [[Epic - Composable transport layer parity]] — no direct
coupling; AWG is UDP-only and composes nothing.

## Risks / open questions

- boringtun fork drift: upstream boringtun keeps moving (Cloudflare
maintains it for WARP). Decide upfront whether we track upstream or
hard-fork. Likely: maintain as a separate crate, cherry-pick
upstream CVE fixes.
- AWG 2.0 specification stability: `I1`–`I5` semantics in amneziawg-go
v0.2.16 are still evolving. Pin a known-good amneziawg-go version
as the reference implementation for test-vector generation.
- Handshake-timing detection: non-zero Jc delays initiation by the
time spent sending junk packets. Verify this does not trip RIPDPI's
own direct-mode verdict state machine (which expects timely
initiations).
- uTLS / fingerprint interactions: AWG is UDP-only; there is no TLS
ClientHello to spoof. But if the server sits behind a TLS-over-UDP
obfuscation layer (some deployments do this), the AWG stack must
not assume it owns the raw UDP socket.
- License: boringtun is BSD-3; amneziawg-go is MIT. Any code ported
from amneziawg-go must carry MIT attribution; do not mix
boringtun BSD-3 source with MIT-derived AWG patches without clear
file-level licensing headers.

## Links

- [[ripdpi-android]]
- [[Add WireGuard INI subscription parser]]
- Reference implementation: https://github.com/amnezia-vpn/amneziawg-go
- Reference Android client: https://github.com/amnezia-vpn/amneziawg-android (local: `/Users/po4yka/GitRep/amneziawg-android/`)
- Child issues: 6
