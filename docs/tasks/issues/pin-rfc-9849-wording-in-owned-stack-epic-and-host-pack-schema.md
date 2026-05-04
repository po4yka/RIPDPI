---
title: Pin RFC 9849 wording in owned-stack epic and host-pack schema
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Pin RFC 9849 wording in owned-stack epic and host-pack schema #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Summary

Replace draft-ietf-tls-esni-25 references with RFC 9849 across the owned-
stack epic and the host-pack schema, and verify Conscrypt ECH API names
against the stable RFC vocabulary.

## Research citation

[[ripdpi-android-research-2026-04-20]] §Standards and protocol activity —
RFC 9849 was ratified in 2026; existing RIPDPI documents still cite the
draft. Bumping the reference prevents future schema reviewers from
chasing a superseded draft.

## Acceptance criteria

- [ ] Epic body and host-pack schema reference RFC 9849, not
    draft-ietf-tls-esni-25.
- [ ] Conscrypt ECH API names in code comments and docs verified against
    the stable RFC vocabulary.
- [ ] Decision-block citation list on [[Epic - Owned-stack mode with Android 17 ECH]]
    updated accordingly.

## Links

- [[Epic - Owned-stack mode with Android 17 ECH]]
- [[Parse HTTPS SVCB records with ECH config metadata]]
- [[ripdpi-android-research-2026-04-20]]
