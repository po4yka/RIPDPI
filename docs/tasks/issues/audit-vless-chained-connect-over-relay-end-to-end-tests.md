---
title: Audit VLESS chained connect_over relay end-to-end test coverage
type: task
status: backlog
area: testing
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Audit VLESS chained connect_over relay end-to-end test coverage #repo/RIPDPI #area/testing #status/backlog 🔼

## Summary

`VlessRealityClient::connect_over` layers a second VLESS+Reality session on top of an existing transport for chain relay. Audit test coverage and add e2e tests if missing.

## Context

Chain relay is a multi-hop tunnel where the second hop's TLS is nested inside the first hop's TLS. The `connect_over` path is implemented but its test surface is unclear from the file listing.

## Acceptance criteria

- [x] The current task note names what is and is not covered today; the obsolete point-in-time audit file was removed on 2026-05-28.
- [ ] At least one end-to-end test drives data through a two-hop chain on loopback, asserting bidirectional payload integrity.
- [ ] One negative test asserts that a chain failure on the second hop surfaces a recognizable error class to the caller.

## Definition of done

- The chain happy path and at least one failure path are exercised in CI.

## Links

- [[Epic - Control-plane hardening]]
