---
title: Add WireGuard INI subscription parser
type: task
status: done
area: outbound
priority: high
owner: unassigned
parent: epic-subscription-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [x] #task Add WireGuard INI subscription parser #repo/RIPDPI #area/outbound #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-wireguard-ini-subscription-parser`
- **Verify:** `just test-module core:data:runtime-state`
- **Scope (only modify these + this file + the ledger):** `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Parse standard `.conf`-style WireGuard INI payloads (multi-peer supported)
into one WireGuard profile per peer.

## Context

Subscription providers sometimes distribute WireGuard nodes as raw INI,
including WARP-compatible layouts. Detection marker is `[Interface]`
presence. Multiple `[Peer]` sections produce multiple profiles sharing the
interface key material; surface them clearly in the populated group.

## Acceptance criteria

- [ ] Detect INI via `[Interface]` header presence.
- [ ] Parse `[Interface]` (PrivateKey, Address, DNS, MTU) and each `[Peer]`
    (PublicKey, AllowedIPs, Endpoint, PresharedKey, PersistentKeepalive).
- [ ] Produce one WireGuard profile per peer, sharing the interface
    keypair and distinguishing by peer endpoint in display name.
- [ ] Preserve `AllowedIPs` as per-profile routing hint even if the
    runtime currently ignores it; keep for future routing epic.
- [ ] Malformed INI surfaces a typed error; per-peer failures degrade to
    "skip and warn", not full subscription rejection.
- [ ] Unit tests cover: single-peer, multi-peer, WARP-style config, DNS
    field present and absent, IPv4-only and dual-stack AllowedIPs.

## Source references

**Reference implementation notes:**

- `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — method `parseWireGuard(text)`. Detection: `text.contains("[Interface]")`. Uses `org.ini4j.Ini` to parse.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/wireguard/` — the `WireGuardBean` field set that receives parsed values.

**amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — the AWG-extended INI parser is the definitive reference for Jc/Jmin/Jmax/S1-S4/H1-H4/I1-I5 key handling:

- `tunnel/src/main/java/org/amnezia/awg/config/Config.java` (`parse(InputStream)` starting line 50) — section dispatch on `[Interface]` / `[Peer]`.
- `tunnel/src/main/java/org/amnezia/awg/config/Interface.java:101-184` — the per-key `switch` that parses every AWG extension key. **Port this switch verbatim** for the [[Wire AmneziaWG into the subscription WireGuard-INI parser]] follow-on task.

**Adapt:** Detection marker, per-section header handling, per-peer profile emission. **Skip:** Reference implementation's `ini4j` dependency if RIPDPI already has an INI parser; otherwise add it. Use `ini4j` 0.5.4 (same version reference implementation pins) for parity.

## Links

- [[Epic - Subscription and profile import]]

## Work log

- 2026-05-14 — Implemented test-first.
- **Files created:**
  - `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/subscription/WireGuardIniSubscriptionParser.kt`
    — new `WireGuardIniSubscriptionParser` + `WireGuardSubscriptionProfile` /
    `WireGuardIniSubscriptionResult`. Detects via `[Interface]` presence;
    builds on the existing `WireGuard ConfParser` (does not reimplement INI
    scanning). To get per-peer failure isolation out of the all-or-nothing
    config parser, the payload is split into its `[Interface]` block + one
    block per `[Peer]`, and each `[Interface]+[Peer]` pair is parsed
    independently. Preserves `AllowedIPs` as a per-profile routing hint;
    malformed INI / missing `[Interface]` → typed `SubscriptionLineWarning`;
    a bad `[Peer]` degrades to skip-and-warn.
  - `core/data/src/test/kotlin/com/poyka/ripdpi/data/WireGuardIniSubscriptionParserTest.kt`
    — 9 tests: detection, single-peer, multi-peer (shared interface keypair),
    WARP-style dual-stack, DNS present/absent, IPv4-only + dual-stack
    AllowedIPs, malformed INI, no-`[Interface]`, per-peer skip-and-warn.
- **Red-then-green:** initial run RED — `a peer missing its public key
  degrades to skip-and-warn` failed `expected:<1> but was:<0>` because
  `WireGuardConfParser.parse` is all-or-nothing and threw on the bad peer,
  rejecting the whole config. Fixed by per-peer block splitting; all 9 green.
- **Verify (orchestrator-pinned):** `./gradlew :core:data:testDebugUnitTest`
  — `BUILD SUCCESSFUL`, exit code 0 (419 tests, 0 failed; this task's 9 green).
- **Scope note:** the issue's `Verify` was
  `just test-module core:data:runtime-state`; the orchestrator pinned
  `./gradlew :core:data:testDebugUnitTest` and placed the test under
  `core/data/src/test/**` (which has `:core:data:runtime-state` on its API
  classpath). No `ini4j` dependency was added — `libs.versions.toml` is out
  of scope and the existing `WireGuardConfParser` already covers INI scanning.
- **Residual risk:** the standalone `WireGuardSubscriptionProfile` is not yet
  a `ProxyProfile` variant nor persisted; wiring it into the subscription
  import pipeline + group population is follow-on work.
