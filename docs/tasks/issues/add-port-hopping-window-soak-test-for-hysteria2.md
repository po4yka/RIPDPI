---
title: Add port-hopping window soak test for Hysteria 2
type: task
status: doing
area: testing
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-31
---

## Summary

`ripdpi-hysteria2/src/port_hopping.rs` is 14 KB of stateful logic that rebinds the local UDP socket on a recurring schedule. Add a soak test that runs through many hop windows while injecting path-MTU shifts and brief loss spikes.

## Context

Port hopping is a transport-evasion feature whose failure modes are hard to surface in unit tests: a stuck endpoint, a leaked socket, or an off-by-one in the hop schedule may only appear over minutes of operation.

## Acceptance criteria

- [x] (partial, 2026-05-15, scope-bound) Window-iteration soak is already covered by `telemetry_surfaces_a_non_degenerate_distribution_for_a_randomized_window` (2000 iterations through `PortHoppingWindow::next_interval` with min/max/spread/mean assertions and telemetry count checks). The test is in `ripdpi-hysteria2::port_hopping::tests` and runs in standard CI.
- [ ] The test asserts: no leaked sockets, monotonic hop indices, and bidirectional bytes delivered every window. **DEFERRED:** the existing soak covers the window/telemetry layer; the socket-rebind soak (testing `endpoint.rs::rebind_endpoint` across many hops) requires a long-running loopback Quinn server harness that is shared with `add-quic-path-mtu-discovery-regression-test` and `add-shadowtls-loopback-test-server-for-soak-runs`. Track that shared harness as a separate follow-up.
- [x] (2026-05-15) `HopIntervalTelemetry` counters match the asserted hop count — already asserted by `telemetry_records_each_recorded_interval` / `telemetry_surfaces_a_non_degenerate_distribution_for_a_randomized_window`.
- [ ] A nightly CI lane runs the soak; PR CI does not. **DEFERRED:** the existing tests run in PR CI (2000 iterations completes quickly). A separate nightly socket-rebind soak lane will land with the shared harness above.

## Definition of done

- A regression that breaks hop scheduling after window N>10 is caught by the nightly soak.

## Links

- [[add-protocol-throughput-benchmarks-for-each-transport]]
- [[add-quic-path-mtu-discovery-regression-test]]
