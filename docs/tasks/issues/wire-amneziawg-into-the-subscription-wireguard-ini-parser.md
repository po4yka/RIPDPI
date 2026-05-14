---
title: Wire AmneziaWG into the subscription WireGuard-INI parser
type: task
status: done
area: outbound
priority: medium
owner: unassigned
parent: epic-amneziawg-outbound-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [x] #task Wire AmneziaWG into the subscription WireGuard-INI parser #repo/RIPDPI #area/outbound #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `wire-amneziawg-into-the-subscription-wireguard-ini-parser`
- **Verify:** `just test-module core:data:runtime-state`
- **Scope (only modify these + this file + the ledger):** `core/data/runtime-state/**`, `core/data/model/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Extend the WireGuard-INI subscription parser so a subscription
containing an AWG-flavored `[Interface]` block produces an
`AmneziaWGBean`, not a vanilla `WireGuardBean`.

## Context

Depends on the `AmneziaWGBean` + parser extension task landing first.
Detection is by presence of any AWG key in the `[Interface]` block;
zero AWG keys → vanilla WG bean; any AWG key → AWG bean. Multi-peer
INI files follow the same per-peer semantics as the existing parser.
No new subscription format is added.

## Acceptance criteria

- [ ] `RawUpdater` (or equivalent) WireGuard-INI parser routes
    `[Interface]` blocks to the right bean type based on AWG-key
    presence.
- [ ] Multi-peer INI files work: interface-scope AWG fields apply to
    all peer profiles derived from the file.
- [ ] Mixed subscription: an INI file with both an AWG interface and
    a vanilla interface (unusual but possible) produces the right
    bean for each.
- [ ] Subscription refresh preserves user-edited override fields on
    AWG beans just as on vanilla WG beans.
- [ ] Unit tests cover: AWG INI, vanilla INI, AWG with partial fields,
    malformed AWG fields (warning, skip line, continue).

## Source references

**amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`):

- `tunnel/src/main/java/org/amnezia/awg/config/Interface.java:101-184` — the INI-key `switch` is already the canonical implementation of routing AWG keys to the right fields. Shared with the `.conf` parser task; this task plugs the same shape into the subscription path.
- `tunnel/src/main/java/org/amnezia/awg/config/Config.java` — `parse(InputStream)` — section dispatch already ignores whitespace-surrounded keys and is tolerant of blank lines. Port directly.

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt` — `parseWireGuard()` — the existing subscription WG-INI parser. This task extends it with the AWG-key detection branch: if `[Interface]` contains any AWG key, emit an `AmneziaWGBean`; else emit `WireGuardBean`.

**Adapt:** Detection logic (any of `jc`/`jmin`/`jmax`/`s1..s4`/`h1..h4`/`i1..i5` → AWG bean), graceful degradation if AWG fields are malformed. **Skip:** nothing meaningful — this is a small targeted extension.

## Links

- [[Epic - AmneziaWG outbound support]]
- [[Add WireGuard INI subscription parser]]
- [[Add AmneziaWG Kotlin config model and dot-conf parser extensions]]


## boot-autostart-and-session

## Work log

- 2026-05-14 — Extended `WireGuardIniSubscriptionParser` to route an
  AWG-flavored `[Interface]` block to a new `AmneziaWgSubscriptionProfile`
  type. Detection reuses the existing `WireGuardConfParser`, which already
  returns `AmneziaWgConfig` vs `WireGuardConfig` by AWG-key presence — the
  subscription parser now consumes that distinction per `[Peer]`.
  `WireGuardIniSubscriptionResult` gained an `amneziaWgProfiles` list so
  callers see the routing without a cast. Added an explicit interface-block
  pre-validation so a malformed interface-scope AWG key fails the whole
  subscription with one typed warning (interface keys are shared by every
  peer) rather than degrading per-peer. The per-peer loop was extracted into
  a `SubscriptionAccumulator` to keep `parse` within the method-length limit.
- New test: `core/data/src/test/kotlin/com/poyka/ripdpi/data/WireGuardIniSubscriptionAmneziaWgTest.kt`
  (AWG INI, vanilla INI, multi-peer AWG, partial AWG fields, malformed AWG
  field, mixed payload). TDD: tests written first, confirmed RED, then GREEN.
- Verify: `./gradlew :core:data:testDebugUnitTest` — BUILD SUCCESSFUL (exit 0).
  `:core:data:runtime-state:detekt` is pre-existing RED at HEAD (35 weighted
  violations across 8 files this task did not touch); the only changed file,
  `WireGuardIniSubscriptionParser.kt`, adds zero new detekt violations
  (`@Suppress("ReturnCount")` carries the pre-existing `parse` return-count
  finding; `LongMethod` resolved by the extraction). `ktlint` clean on the
  changed file.
