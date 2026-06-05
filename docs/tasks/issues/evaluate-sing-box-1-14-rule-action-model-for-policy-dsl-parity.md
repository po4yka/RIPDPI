---
title: Evaluate sing-box 1.14 rule-action model for policy DSL parity
type: task
status: backlog
area: diagnostics
priority: low
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-06-05
---

## Summary

Summarize sing-box 1.14's rule-action model, then decide whether RIPDPI's direct-mode transport-policy DSL should align vocabulary with it or deliberately diverge.

## Research citation

ripdpi-android-research-2026-04-20 §Upstream transport engines — sing-box 1.14.0-alpha.13 (2026-04-17) replaces legacy inbound/outbound-special-field plumbing with a rule-action model that supports pre-matching. Aligning (or explicitly diverging with rationale) makes it cheaper to exchange strategy expressions with the peer community.

## Acceptance criteria

- [ ] sing-box 1.14 rule-action vocabulary summarized (matchers, action types, pre-match semantics).
- [ ] Alignment-vs-divergence decision recorded with rationale on [[Epic - Direct-mode transport policy and verdicts]].
- [ ] If alignment chosen: migration sketch for existing `TransportPolicy` struct noted; no migration work performed in this spike.

## Links

- [[Epic - Direct-mode transport policy and verdicts]]
- [[Define TransportPolicy struct and per-host state]]
- Cache transport policy per network and host tuple (closed task)
- ripdpi-android-research-2026-04-20

## Work log

- 2026-06-05: Research spike not started; no sing-box 1.14 vocabulary summary or alignment decision found anywhere in docs/adr/; TransportPolicy struct exists and is operational (core/data/model/.../TransportPolicy.kt) but the comparison/evaluation has not been performed; parent epic epic-direct-mode-transport-policy-and-verdicts is dangling (not in known epics list), nulled out.
