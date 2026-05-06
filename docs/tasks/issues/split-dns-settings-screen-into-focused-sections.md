---
title: Split DNS settings screen into focused sections
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Split DNS settings screen into focused sections #repo/RIPDPI #area/ui #status/backlog 🔼

## Summary

`DnsSettingsScreen` is suppressed for `LongMethod` and
`CyclomaticComplexMethod`, and the architecture gate reports the composable at
431 lines. Split resolver catalog data, local text state, validation,
mode/protocol controls, custom resolver editors, and save actions.

## Audit citation

- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/dns/DnsSettingsScreen.kt` lines 77-156.

## Scope

- In scope: DNS settings screen sections, local text state, validation,
  resolver catalog presentation, protocol/mode controls, custom resolver
  editors, and save-action rows.
- Out of scope: changing DNS resolver defaults or persistence semantics.

## Acceptance criteria

- [ ] Main DNS settings composable delegates to focused section composables.
- [ ] Local text state and validation are isolated from catalog presentation.
- [ ] LongMethod/CyclomaticComplexMethod suppressions are removed or no longer
    needed for the main composable.
- [ ] DNS settings tests and screenshots stay green.

## Links

- [[Epic - Finish SRP residual architecture debt]]
