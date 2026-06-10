---
title: Add QUIC path-MTU discovery regression test
type: task
status: todo
area: testing
priority: medium
owner: unassigned
parent: epic-protocol-conformance-tests
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-06-10
---

## Summary

Path-MTU shifts (carrier handover, VPN nesting, jumbo-frame paths) break QUIC connections quietly. Add a regression test that simulates a mid-connection MTU drop and asserts the QUIC stack recovers.

## Context

Hysteria 2, TUIC, and MASQUE all run over Quinn. Quinn's PMTUD behaviour is configurable but easy to misconfigure. A small deliberate MTU drop in a loopback harness should be a recoverable event, not a connection kill.

## Acceptance criteria

- [ ] A shared `quic_mtu_test_util` (under a `dev-dependencies` crate or a `tests/common/`) injects an MTU drop on a loopback UDP socket.
- [ ] Each of Hysteria 2, TUIC, and MASQUE has one regression test asserting connection survival and payload integrity after the drop.
- [ ] The test runs in CI's standard test lane (not nightly).

## Definition of done

- A Quinn configuration regression that disables PMTUD fails the test.

## Links

- [[add-port-hopping-window-soak-test-for-hysteria2]]

## Work log

- 2026-06-05: No `quic_mtu_test_util` crate or MTU test exists; no mtu/pmtud references in ripdpi-hysteria2, ripdpi-tuic, or ripdpi-masque; all acceptance criteria unmet — work not started.
