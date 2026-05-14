---
title: Add client compatibility regression matrix for fleet profiles
type: task
status: done
area: testing
priority: medium
owner: unassigned
parent: epic-vpn-fleet-testing-matrix-and-release-gates
blocks: []
blocked_by: []
created: 2026-05-01
updated: 2026-05-14
---

- [x] #task Add client compatibility regression matrix for fleet profiles #repo/RIPDPI #area/testing #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-client-compatibility-regression-matrix-for-fleet-profiles`
- **Verify:** `just lint`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Define a compatibility regression matrix for fleet profiles across custom
Android, sing-box SFA, v2rayNG, NekoBox, husi, Streisand/V2Box, v2rayN, and
sing-box CLI.

## Context

Different clients parse subscriptions, route policy, DNS, TUN, IPv6, and core
versions differently. Compatibility tests need to record both app and embedded
core versions.

## Acceptance criteria

- [x] Shared matrix covers import, credential scope, selector, urltest, TUN,
    kill switch, DNS, IPv6, network transitions, revocation, logs, and
    update migration.
- [x] sing-box/SFA tests cover config check, selector/urltest, degraded
    Cloudflare exclusion, strict route, DNS hijack, rule-set update, and
    revoked profile removal.
- [x] v2rayNG tests cover VLESS+REALITY URI import, per-device subscription,
    VPN mode, Android lockdown, DNS/IPv6 leak checks, core update, and
    Hysteria2 fallback where present.
- [x] NekoBox/husi tests treat subscriptions as nodes-only unless full policy is
    explicitly supported and verify routing/DNS separately.
- [x] iOS tests cover URI/subscription import, manual fallback, DNS/IPv6 leak,
    sleep/wake, Wi-Fi/LTE, and app update persistence.
- [x] v2rayN tests cover Xray core, sing-box core if used, TUN elevation,
    Windows firewall kill switch, DNS/IPv6 leak, and core update behavior.
- [x] Custom Android tests cover VpnService lifecycle, `protect()` for tunnel
    sockets, `onRevoke()`, virtual DNS, no `allowBypass`, IPv4-only behavior,
    package visibility, foreground service resilience, no log secrets, and
    profile signature/expiry/revocation.

## Notes

This matrix coordinates existing RIPDPI client tasks with fleet profile
compatibility.

## Work log

- 2026-05-14: Implemented the client-compatibility regression matrix as a
  real, runnable test under `core/service/src/test/**` (in scope for this
  task's `core/service/**`).
  - `fleetcompat/FleetClientCompatMatrix.kt` — the matrix itself: all 8
    enumerated `FleetClient`s (custom Android, sing-box/SFA, v2rayNG, NekoBox,
    husi, Streisand/V2Box, v2rayN, sing-box CLI) x the shared 12-dimension
    cross-client surface plus per-family dimensions (sing-box/SFA, v2rayNG,
    nodes-only, iOS, v2rayN-desktop, custom-Android). Pure data + pure
    predicates: `dimensionsFor`, `cellsFor`, `allCells`, `isNodesOnly` /
    `isFullPolicy`, and a `validate()` invariant pass. Every `CompatCell`
    records both app and embedded core versions.
  - `fleetcompat/FleetClientCompatMatrixTest.kt` — the runnable release gate:
    asserts the shared-dimension set, per-family dimension sets (sing-box,
    v2rayNG, iOS, v2rayN, custom-Android fail-closed invariants), nodes-only
    vs full-policy classification for NekoBox/husi, no orphan/leaked
    dimensions, cell-count consistency, and zero structural validation issues.
  - Builds on the phase-1/2 golden-file harness conceptually: the
    `FleetCompatHarness`/`FleetCompatGoldenFileTest` lock the *bundle-import*
    surface; this matrix locks the *client-coverage* surface on top of it
    (documented in both files' headers).
  - TDD red->green confirmed: a deliberately wrong expectation
    (`FleetClient.entries.size` 8 -> 99) made the suite fail, then reverted to
    green.
- **Verify:** `just lint` (= `./gradlew staticAnalysis`) -> BUILD SUCCESSFUL,
  exit 0. `just lint` runs detekt + ktlint + Android Lint but not unit tests,
  so the matrix test was additionally executed via
  `./gradlew :core:service:testDebugUnitTest` -> BUILD SUCCESSFUL, exit 0.

## Links

- [[Add Xray VPN client regression matrix]]
- [[Add Android VPN leak-test instrumentation matrix]]
- [[Add per-device subscription token UX and shared-link warnings]]
- [[Epic - Fail-closed Android VPN policy engine]]
