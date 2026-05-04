---
title: Implement Bayesian posterior arm scoring
type: task
status: backlog
area: service
priority: high
owner: unassigned
parent: epic-privacy-preserving-strategy-learner
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Implement Bayesian posterior arm scoring #repo/RIPDPI #area/service #status/backlog ⏫

## Summary

Score arms using Beta posterior with performance and rarity penalties:

```text
posterior = alpha / (alpha + beta)
score = posterior
    - 0.10 * normalized_ttfb
    - 0.08 * normalized_bytes_overhead
    - 0.15 * repeated_attempt_penalty
    - 0.20 * rarity_penalty
```

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §5 arm ranking.

## Acceptance criteria

- [ ] Scorer consumes `ArmStats` + `NetProfile` + `HostProfile`.
- [ ] Normalization of TTFB and byte overhead is network-profile-aware
    (cellular vs wifi baselines differ).
- [ ] Ties are broken deterministically but with a small randomization to
    avoid consistent arm preference.
- [ ] Unit tests cover each weighting term in isolation.

## Links

- [[Define NetProfile HostProfile and ArmStats]]
- [[Add rarity and repeated-attempt penalties to arm ranking]]
- [[Epic - Privacy-preserving strategy learner]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
