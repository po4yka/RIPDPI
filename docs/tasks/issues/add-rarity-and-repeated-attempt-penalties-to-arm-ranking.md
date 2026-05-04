---
title: Add rarity and repeated-attempt penalties to arm ranking
type: task
status: backlog
area: service
priority: medium
owner: unassigned
parent: epic-privacy-preserving-strategy-learner
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Add rarity and repeated-attempt penalties to arm ranking #repo/RIPDPI #area/service #status/backlog 🔼

## Summary

`rarity_penalty`: high for rare, distinctive wire images — protects
against accumulation-based detection. `repeated_attempt_penalty`: grows
when we keep hammering the same host with failures — protects against
pattern pinning and battery burn.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §5.

## Acceptance criteria

- [ ] Rarity is computed from local-observed arm frequency, not a preset
    label.
- [ ] Penalty resets appropriately when the network profile changes (new
    observation window).
- [ ] Repeated-attempt penalty is per `(host, NetProfile)`, not global.
- [ ] Unit tests: rare arm wins tie-break only when posterior is high
    enough to justify it; repeated-attempt penalty caps after N
    consecutive failures.

## Links

- [[Implement Bayesian posterior arm scoring]]
- [[Epic - Privacy-preserving strategy learner]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
