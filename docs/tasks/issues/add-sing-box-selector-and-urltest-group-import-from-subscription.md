---
title: Add sing-box selector and urltest group import from subscription
type: task
status: backlog
area: outbound
priority: critical
owner: unassigned
parent: epic-ripdpi-vpn-deploy-fleet-compatibility
blocks: []
blocked_by: []
created: 2026-05-14
updated: 2026-05-14
---

- [ ] #task Add sing-box selector and urltest group import from subscription #repo/RIPDPI #area/outbound #status/backlog 🔺

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-sing-box-selector-and-urltest-group-import-from-subscription`
- **Verify:** `./gradlew :core:data:runtime-state:testDebugUnitTest`
- **Scope (only modify these + this file + the ledger):** `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Objective

Promote the `selector` + `urltest` outbound pair the deployer always
emits into a RIPDPI **ProxyGroup** with an auto-failover policy, so a
multi-host / multi-cohort bundle imports as one switchable group with
latency-driven failover — instead of a flat list of unrelated
profiles.

## Context

### What the deployer emits

`emit-singbox.sh:338-350` always wraps the concrete outbounds in a
`selector` + `urltest` pair:

```json
{
  "outbounds": [
    { "type": "vless",     "tag": "p0-reality-upcloud-prod", ... },
    { "type": "hysteria2", "tag": "p2-hysteria2-upcloud-prod", ... },
    { "type": "vless",     "tag": "p1-xhttp-hetzner-prod", ... },
    { "type": "selector",  "tag": "select",
      "outbounds": ["p0-reality-upcloud-prod","p2-hysteria2-upcloud-prod","p1-xhttp-hetzner-prod","auto"],
      "default": "auto",
      "interrupt_exist_connections": false },
    { "type": "urltest",   "tag": "auto",
      "outbounds": ["p0-reality-upcloud-prod","p2-hysteria2-upcloud-prod","p1-xhttp-hetzner-prod"],
      "url": "https://www.gstatic.com/generate_204",
      "interval": "5m", "tolerance": 50 }
  ]
}
```

The concrete outbounds are emitted one per `(host, cohort, transport)`
tuple (`:211-327`). The `selector` is the user-switchable group; the
`urltest` is the auto-failover policy.

### The gap

The base parser ([[Add sing-box JSON subscription parser]]) maps each
**concrete** outbound to a profile bean but does not recognise
`selector` / `urltest` as group metadata. The result today would be a
flat list of profiles with no group and no failover.

### Required mapping

| sing-box entry | RIPDPI |
|---|---|
| concrete outbound (`vless`/`hysteria2`/…) | relay profile |
| `selector` | `ProxyGroup` with `isSelector=true`, member order from `outbounds[]` minus the urltest tag, `defaultMemberTag` from `default` |
| `urltest` | the group's failover policy: `probeUrl`, `intervalSeconds`, `toleranceMs` — feeding [[Add priority-based outbound failover state machine]] |

Runtime switching already exists
([[Add selector outbound runtime for group-based profile switching]]);
this task wires only the **importer**.

## TDD workflow

Implement strictly test-first per the epic TDD policy.

1. **Red** — author these and confirm each fails before
   implementation:
   - `core/data/runtime-state/src/test/kotlin/.../SelectorUrltestImportTest.kt`
     — feeds the full bundle above; asserts 3 profiles + 1
     ProxyGroup with correct member order, `defaultMemberTag`, and
     a failover policy (`probeUrl`, `interval=300s`, `tolerance=50`).
     *Fails: parser ignores selector/urltest.*
   - same file, **forward-reference case** — selector listed
     *before* the outbounds it names; assert tag resolution still
     succeeds. *Fails: order-dependent resolution.*
   - same file, **selector-only case** — selector present, no
     urltest; assert a ProxyGroup with `failoverPolicy = MANUAL`.
     *Fails: NPE / policy assumed.*
   - same file, **single-outbound case** — one concrete outbound, no
     selector; assert a single profile and **no** group. *Fails:
     spurious group created.*
   - same file, **tag-not-found case** — selector names a tag absent
     from `outbounds`; assert a typed error naming the missing tag.
     *Fails: silent drop / NPE.*
   - `core/data/runtime-state/src/test/kotlin/.../SelectorRefreshMergeTest.kt`
     — refresh: same-tag profiles update in place; group membership
     reflects additions/removals; the active selection is preserved
     if its tag survives, else falls back to `default`. *Fails: no
     diff-merge.*
   - redaction harness extension — live urltest results (latency,
     last-probe timestamp) are surfaced **without** server
     hostnames. *Fails: hostname leaks.*
2. **Confirm failures** — record observed messages in the Work log.
3. **Green** — add the selector/urltest recognition, two-pass tag
   resolution, group construction, failover-policy mapping, and the
   refresh diff-merge — minimal to pass.
4. **Refactor** — share the tag-resolution pass with any existing
   reference-resolution code; re-run, stay green.
5. **Verify** — run `## Completion criteria` commands; attach output.

## Acceptance criteria

- [ ] Parser recognises `type:"selector"` and `type:"urltest"` as
    group metadata, not profiles.
- [ ] Tag → profile resolution is order-independent (two-pass);
    forward references resolve.
- [ ] The generated ProxyGroup has: `isSelector=true`; member order
    matching the selector `outbounds` array minus the urltest tag;
    `defaultMemberTag` from `default`; failover policy from the
    urltest entry (`probeUrl`, `intervalSeconds`, `toleranceMs`).
- [ ] Single concrete outbound + no selector → one profile, no
    group.
- [ ] Selector present + no urltest → ProxyGroup with
    `failoverPolicy = MANUAL`.
- [ ] urltest present + no selector → the orphaned urltest is
    skipped (logged at debug), no group created.
- [ ] Selector naming a missing tag → typed error naming the tag;
    the import is rejected as a unit (no partial group).
- [ ] Refresh diff-merges: same-tag profiles update in place; group
    membership reflects additions and removals; active selection
    preserved if its tag survives, else falls back to `default`.
- [ ] Diagnostics surface per-member live urltest results without
    leaking server hostnames.

## Test plan

| Layer | File | Cases |
|---|---|---|
| Kotlin unit | `SelectorUrltestImportTest.kt` | full bundle; forward-ref; selector-only; single-outbound; tag-not-found |
| Kotlin unit | `SelectorRefreshMergeTest.kt` | in-place update; membership add/remove; active-selection preservation + fallback |
| Kotlin unit | redaction harness | urltest latency present, hostname absent |
| Golden-file | fleet suite (`multi-host-failover/`) | covered by [[Add ripdpi-vpn-deploy fleet compatibility golden-file tests]] |

## Completion criteria

`#status/done` only when **every** item holds, with evidence in the
`## Work log`:

- [ ] All `## Acceptance criteria` checkboxes checked.
- [ ] All test files exist, written **before** implementation
    (red-then-green confirmed in the Work log), and pass.
- [ ] `./gradlew :core:data:runtime-state:testDebugUnitTest` green —
    output attached.
- [ ] The `multi-host-failover/` golden-file fixture imports to
    exactly 4 profiles + 1 ProxyGroup with the urltest policy
    applied — asserted by the fleet suite, output attached.
- [ ] An instrumented test confirms on-the-wire urltest probe
    traffic is observable after import (failover is live, not just
    parsed).
- [ ] `./gradlew lintDebug` clean.
- [ ] Redaction test green.
- [ ] Reviewed by a separate `code-reviewer` pass.
- [ ] `## Work log` added: changed files, test output, residual
    risk.

## Source references

- Deployer selector + urltest emission:
  `ripdpi-vpn-deploy/scripts/emit-singbox.sh:338-350`
- Deployer concrete outbound emission (per-cohort tags):
  `ripdpi-vpn-deploy/scripts/emit-singbox.sh:211-327`
- Runtime side (already exists):
  [[Add selector outbound runtime for group-based profile switching]]
- Failover state machine:
  [[Add priority-based outbound failover state machine]]
- sing-box selector spec:
  https://sing-box.sagernet.org/configuration/outbound/selector/
- sing-box urltest spec:
  https://sing-box.sagernet.org/configuration/outbound/urltest/

## Links

- [[Epic - ripdpi-vpn-deploy fleet compatibility]]
- [[Add sing-box JSON subscription parser]]
- [[Add selector outbound runtime for group-based profile switching]]
- [[Add priority-based outbound failover state machine]]
- [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]
- [[Add ripdpi-vpn-deploy fleet compatibility golden-file tests]]
