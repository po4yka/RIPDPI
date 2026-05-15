---
title: Add RuleEntity Room table and repository
type: task
status: done
area: routing
priority: high
owner: unassigned
parent: epic-advanced-routing-rules-and-geoip-enforcement
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-16
---

- [x] #task Add RuleEntity Room table and repository #repo/RIPDPI #area/routing #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-ruleentity-room-table-and-repository`
- **Verify:** `just test-module core:data`
- **Scope (only modify these + this file + the ledger):** `core/data/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a `RuleEntity` Room table and repository that models user-editable
routing rules: domain / CIDR / port / process / package matchers and
proxy / bypass / block / specific-profile outbound actions.

## Context

Schema should mirror Reference implementation's RuleEntity for subscription portability
hopes, but without sing-box-only fields (e.g. `network`/`protocol` that
sing-box uses internally). Store matcher lists as newline-delimited
strings (Kotlin), parsed on load; matcher semantics live in the Rust
engine task.

## Acceptance criteria

- [ ] Entity fields: id, name, userOrder, enabled, domains, ipCidrs,
    ports, sourcePorts, network (tcp|udp|both), processName,
    packages (Set<String>), outboundTag (enum: PROXY | BYPASS |
    BLOCK | PROFILE(profileId) | GROUP(groupId)).
- [ ] Repository exposes CRUD and a reorder operation; returns rules
    as a `Flow<List<RuleEntity>>`.
- [ ] Constraint: deletion of a profile/group referenced by any rule
    either cascades (skipping the reference) or prompts the user —
    decide once and document; never silent-corrupt.
- [ ] Seeded default rules: one "bypass LAN" rule, one "bypass
    loopback" rule; user can delete them.
- [ ] Schema is exported from Room and covered by a migration test.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/database/RuleEntity.kt` — the full `@Entity`. Field-for-field port target: `id`, `name`, `userOrder`, `enabled`, `config` (raw JSON override), `domains`, `ip` (CIDR), `port`, `sourcePort`, `network`, `source`, `protocol`, `outbound` (Long with sentinel values: `0` proxy, `-1` bypass, `-2` block, `>0` specific profile), `packages: Set<String>`.
- `app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt` — the DAO: `allRules()`, `enabledRules()`, `checkVpnNeeded()`, CRUD methods. Port the method set.
- `app/src/main/java/io/nekohasekai/sagernet/database/StringCollectionConverter.java` — Room type converter for `Set<String>` (packages list). Port.

**Adapt:** Entity fields, DAO method set, Set<String> converter. **Skip:** Reference implementation's raw-JSON `config` override field (RIPDPI should prefer a stricter typed model; if passthrough is needed, add as a late follow-up).

## Links

- [[Epic - Advanced routing rules and geoip enforcement]]
