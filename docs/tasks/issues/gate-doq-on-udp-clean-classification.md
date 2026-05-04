---
title: Gate DoQ on UDP-clean classification
type: task
status: todo
area: dns
priority: medium
owner: unassigned
parent: epic-encrypted-dns-and-https-svcb-classifier
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-23
---

- [ ] #task Gate DoQ on UDP-clean classification #repo/RIPDPI #area/dns #status/todo 🔼

## Summary

DoQ only as a fast path on networks where UDP/443 is already classified
healthy — otherwise DoQ and QUIC censorship fail together.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §2 operational detail.

## Acceptance criteria

- [x] DoQ is not offered until the transport policy engine has marked
    UDP/443 `udp_ok = true` for the current `NetProfile`.
- [ ] DoQ failure demotes the network to `udp_suspect`, triggering DoH-only
    for the rest of the session.
- [ ] No user-visible toggle — the policy is automatic and coarse-keyed by
    network profile.

## Implementation note

As of 2026-04-23, RIPDPI now enforces the first half of this task on the
live runtime path: if the active encrypted-DNS context is DoQ but the current
authority has a direct-path capability that says UDP/443 is not clean, native
hostname resolution automatically downgrades that authority back to DoH.
What remains open is session-level demotion memory after a live DoQ failure.

## Links

- [[Build DoH primary and secondary resolver pipeline]]
- [[Epic - Encrypted DNS and HTTPS SVCB classifier]]
- [[Epic - Direct-mode transport policy and verdicts]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
