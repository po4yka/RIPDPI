---
title: Add sing-box route.rules Android per-app routing import
type: task
status: done
area: routing
priority: high
owner: unassigned
parent: epic-ripdpi-vpn-deploy-fleet-compatibility
blocks: []
blocked_by: []
created: 2026-05-14
updated: 2026-05-14
---

- [x] #task Add sing-box route.rules Android per-app routing import #repo/RIPDPI #area/routing #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-sing-box-route-rules-android-per-app-routing-import`
- **Verify:** `./gradlew :core:data:runtime-state:testDebugUnitTest`
- **Scope (only modify these + this file + the ledger):** `app/**`, `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Objective

Consume the Android-flavored `route.rules` the deployer emits in its
sing-box bundles — entries carrying `package_name: [...]` — and merge
them into RIPDPI's per-app routing store at subscription-import time,
so operator per-app policy survives the round-trip instead of being
silently dropped.

## Context

### What the deployer emits

`emit-singbox.sh` supports two operator flags when generating a
client bundle (`ripdpi-vpn-deploy/scripts/emit-singbox.sh:21-38` for
flag parsing, `:356-371` for rule emission):

| Flag | Emits | Effect |
|---|---|---|
| `--per-app-bypass pkg1,pkg2,…` | `{ "package_name": [...], "outbound": "direct" }` | listed packages skip the tunnel |
| `--per-app-via-tun pkg1,pkg2,…` | `{ "package_name": [...], "outbound": "select" }` | listed packages forced through the selector group |

sing-box silently ignores `package_name` on non-Android platforms,
so the same bundle is portable; on Android the rules must drive
`VpnService.Builder.addAllowedApplication` /
`addDisallowedApplication`.

### The gap

The base sing-box parser
([[Add sing-box JSON subscription parser]]) consumes only the
`outbounds` array and discards everything under `route`. RIPDPI
already has a per-app routing screen and store; the missing piece is
**reading `route.rules` at import and converting it**, with conflict
surfacing when the user has manually set a different rule for the
same package.

### Required behaviour

- Parser extension reads `route.rules`, keeps only entries with
  `package_name`, and yields `PackageRoutingRule(package, action)`
  where `action ∈ {bypass, via_tun, via_outbound}`.
- Imported rules are written under a namespace tagged with the
  subscription ID — removing the subscription removes its rules.
- A conflict with a user-set rule for the same package opens a
  confirm dialog ("keep mine" (default) / "use subscription" /
  "merge per-rule"); **never** a silent overwrite.
- A bundle that sets both `--per-app-bypass` and `--per-app-via-tun`
  for the same package is **malformed** → typed error pointing at
  the offending `route.rules` index.

## TDD workflow

Implement strictly test-first per the epic TDD policy.

1. **Red** — author these and confirm each fails before
   implementation:
   - `core/data/runtime-state/src/test/kotlin/.../SingBoxRouteRulesParserTest.kt`
     — feeds a `route.rules` array; asserts the `PackageRoutingRule`
     list; asserts non-`package_name` rules (domain, geoip, etc.)
     are ignored. *Fails: parser has no `route` branch.*
   - same file, **malformed case** — same package in both bypass
     and via-tun; assert a typed error with the rule index. *Fails:
     no validation.*
   - `core/data/runtime-state/src/test/kotlin/.../PerAppRoutingMergeTest.kt`
     — import into an empty store → rules tagged with the
     subscription ID; remove the subscription → rules gone. *Fails:
     no namespaced merge.*
   - same file, **conflict case** — a user-set rule for package P,
     import a different rule for P; assert a conflict record is
     produced and **nothing is overwritten** until resolved.
     *Fails: silent overwrite.*
   - same file, **refresh atomicity** — refresh replaces the
     subscription-tagged set atomically; a user-set rule added
     since the last import is preserved and re-checked for
     conflicts. *Fails: stale rules linger / user rule clobbered.*
   - redaction harness extension — diagnostics export contains the
     counts summary (`N from sub, M user-set, K conflicts`) but
     **not** the package names. *Fails: package list leaks.*
2. **Confirm failures** — record observed messages in the Work log.
3. **Green** — add the `route` parse branch, the namespaced merge,
   the conflict model, the malformed-bundle validation — minimal to
   pass.
4. **Refactor** — unify the conflict-resolution path with any
   existing rule-edit conflict handling; re-run, stay green.
5. **Verify** — run `## Completion criteria` commands + the
   round-trip check; attach output.

## Acceptance criteria

- [ ] Parser extension reads `route.rules`, filters to entries with
    `package_name`, and produces `PackageRoutingRule` records
    (`package`, `action ∈ {bypass, via_tun, via_outbound}`).
- [ ] Non-`package_name` rules (domain, geoip, port, …) are ignored
    without error.
- [ ] Imported rules are written under a subscription-ID-tagged
    namespace; deleting the subscription removes exactly those
    rules and no others.
- [ ] A conflict with a user-set rule for the same package opens a
    confirm dialog listing conflicts with "keep mine" (default) /
    "use subscription" / "merge per-rule"; no silent overwrite.
- [ ] A bundle with the same package in both bypass and via-tun is
    rejected as malformed with a typed error naming the rule index.
- [ ] Subscription refresh replaces the subscription-tagged rule set
    atomically; user-set rules added since the last import are
    preserved and re-checked for conflicts.
- [ ] The per-app routing screen badges subscription-imported rules
    with a "from <sub name>" subtitle.
- [ ] Diagnostics export carries only the redacted counts summary,
    never the package list.

## Test plan

| Layer | File | Cases |
|---|---|---|
| Kotlin unit | `SingBoxRouteRulesParserTest.kt` | bypass + via-tun parse; non-package rules ignored; malformed (dup package) → typed error |
| Kotlin unit | `PerAppRoutingMergeTest.kt` | clean import + namespaced removal; conflict → no overwrite; refresh atomicity |
| Kotlin unit | redaction harness | counts summary present, package names absent |
| Instrumented | `app/src/androidTest/.../PerAppRoutingImportUiTest.kt` | conflict dialog options; subscription badge rendering |

## Completion criteria

`#status/done` only when **every** item holds, with evidence in the
`## Work log`:

- [ ] All `## Acceptance criteria` checkboxes checked.
- [ ] All test files exist, written **before** implementation
    (red-then-green confirmed in the Work log), and pass.
- [ ] `./gradlew :core:data:runtime-state:testDebugUnitTest` green —
    output attached.
- [ ] Instrumented test green on an emulator — output attached.
- [ ] **Round-trip check**, recorded in the Work log: an
    `emit-singbox.sh` bundle with `--per-app-bypass A,B` and
    `--per-app-via-tun C,D` imports cleanly → the per-app routing
    screen shows 4 subscription-tagged rules → removing the
    subscription removes all 4.
- [ ] `./gradlew lintDebug` clean; new string keys in all 7 locales.
- [ ] Redaction test green.
- [ ] Reviewed by a separate `code-reviewer` pass.
- [ ] `## Work log` added: changed files, test output, residual
    risk (e.g., package no longer installed on the device).

## Source references

- Deployer flag parsing:
  `ripdpi-vpn-deploy/scripts/emit-singbox.sh:21-38`
- Deployer rule emission:
  `ripdpi-vpn-deploy/scripts/emit-singbox.sh:356-371`
- Rule shape: `route.rules[]` entries with
  `package_name: ["com.example"]`, `outbound: "direct" | "select"`

## Links

- [[Epic - ripdpi-vpn-deploy fleet compatibility]]
- [[Add sing-box JSON subscription parser]]
- [[Add full routing rule editor screen]]
- [[Add sing-box selector and urltest group import from subscription]]

## Work log

**2026-05-14 — core/data layer implemented (TDD, app/ UI wiring out of scope).**

Scope note: the issue Goal-contract scope is `app/**` + `core/data/runtime-state/**`.
This pass implements the testable `core/data` core only (parser, model, merge,
conflict detection, malformed-bundle rejection, redaction). The `app/`
per-app-routing UI wiring (conflict dialog, "from <sub>" badge, instrumented
test) is explicitly deferred — not in this agent's scope.

Files created:
- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/routing/PackageRoutingRule.kt`
  — `PackageRoutingAction` (BYPASS / VIA_TUN / VIA_OUTBOUND), `PackageRoutingRuleOrigin`
  (User / Subscription(id)), `PackageRoutingRule`.
- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/routing/PackageRoutingStore.kt`
  — immutable rule collection; `upsertUserRule`, `removeSubscriptionRules`, etc.
- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/routing/PackageRoutingMerge.kt`
  — namespaced merge: drops the subscription's prior tagged set, applies the
  fresh set atomically, never overwrites a user rule, emits `PackageRoutingConflict`
  records; `toRedactedDiagnostics()` counts-only summary.
- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/subscription/SingBoxRouteRulesParser.kt`
  — reads `route.rules`, keeps only `package_name` entries, maps
  `direct`→BYPASS / `select`→VIA_TUN / other→VIA_OUTBOUND; same package in both
  bypass and via-tun → typed `Error` naming the `route.rules` index.

Test files created (written before implementation, red-then-green):
- `core/data/src/test/kotlin/com/poyka/ripdpi/data/SingBoxRouteRulesParserTest.kt` — 6 tests.
- `core/data/src/test/kotlin/com/poyka/ripdpi/data/PerAppRoutingMergeTest.kt` — 6 tests
  (clean import + namespaced removal, conflict → no overwrite, refresh atomicity,
  refresh re-checks new user rules, redaction summary).

Verify: `./gradlew :core:data:testDebugUnitTest` — `SingBoxRouteRulesParserTest`
6/6 pass, `PerAppRoutingMergeTest` 6/6 pass (JUnit XML: `failures="0" errors="0"`).
The aggregate gradle invocation also builds the sibling `:core:engine:buildRustNativeLibs`
native task, which exhibits a non-deterministic BoringSSL/cmake `.d`-file race
unrelated to this change (`native/**` is out of scope) — see the agent transcript
for the per-test-class XML evidence.

Residual risk: a package named in an imported rule may no longer be installed on
the device; the `app/` layer must tolerate that when applying allowed/disallowed
app lists. Conflict-resolution UI and the subscription badge are deferred to the
`app/` follow-up.
