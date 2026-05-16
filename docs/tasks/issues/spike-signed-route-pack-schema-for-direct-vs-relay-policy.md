---
title: Spike signed route-pack schema for direct-vs-relay policy
type: task
status: done
area: engine
priority: medium
owner: unassigned
parent: epic-control-plane-hardening
blocks: []
blocked_by: []
created: 2026-04-22
updated: 2026-05-16
---

- [x] #task Spike signed route-pack schema for direct-vs-relay policy #repo/RIPDPI #area/engine #status/done 🔼

## Work log

- 2026-05-16: Spike landed at
  `core/engine/docs/spikes/signed-route-pack-schema-2026-05-16.md`.
  Decision: introduce a separately-signed route-pack alongside
  host-pack and strategy-pack. Manifest shape (sequence, issued_at,
  channel, compatibility), canonical JSON wire format with locally
  compiled side-car cache, hourly/daily refresh cadence with
  monotonic sequence anti-rollback, schema-drift fall-through to
  last-good-known, migration example for whitelist-sensitive
  destinations, and explicit must-not list (operator secrets,
  per-user state, raw URLs, identity-correlatable selectors) all
  documented.

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `spike-signed-route-pack-schema-for-direct-vs-relay-policy`
- **Verify:** `just test-module core:engine`
- **Scope (only modify these + this file + the ledger):** `core/engine/**`, `core/data/catalog/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Decide whether RIPDPI should add a signed route-pack layer above host packs and
strategy packs to carry per-destination and per-app direct-vs-relay policy
hints for whitelist-sensitive networks.

## Context

Today's control-plane research points to a gap in the current RIPDPI model.

- [[sing-box-antizapret-control-plane-2026]] frames the hard problem as feed →
rule-set → runtime policy, with integrity, cadence, and schema-drift concerns.
- [[whitelist-oriented-censorship-resilience-2026]] shows that under stronger
allowlist pressure the app needs more than "host present in pack" decisions;
it needs structured policy hints about which lane should stay direct, which
should move to relay, and which should surface owned-stack-only guidance.

RIPDPI already has signed strategy packs and a separate host-pack catalog, but
neither is clearly the right carrier for destination-class policy such as
"domestic direct", "browser fallback preferred", or "owned-stack only".

## Acceptance criteria

- [ ] The spike decides whether route intent belongs in:
    existing host packs, existing strategy packs, or a new signed route-pack
    artifact.
- [ ] The output defines a signed manifest shape with at least `sequence`,
    `issued_at`, `channel`, and compatibility/version fields.
- [ ] The output compares JSON vs compiled/binary runtime formats for the
    policy artifact and records the chosen direction with tradeoffs.
- [ ] The output defines refresh cadence, anti-rollback expectations, and
    schema-drift handling behavior.
- [ ] The output includes one migration example for whitelist-sensitive
    destinations or apps, including a domestic-direct exception path.
- [ ] The output states explicitly what must *not* go into this pack class
    (for example secrets or operator-private material).

## Notes

This is a schema and control-plane spike, not an implementation task. If the
answer is "extend host packs", document why a third pack type is not worth the
operational cost.

## Links

- [[Epic - Control-plane hardening]]
- [[Sign host-pack manifests with app-trusted keys]]
- [[Add anti-rollback to strategy-pack updates]]
- [[sing-box-antizapret-control-plane-2026]]
- [[whitelist-oriented-censorship-resilience-2026]]
