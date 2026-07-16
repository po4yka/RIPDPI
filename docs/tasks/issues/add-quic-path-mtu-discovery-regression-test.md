---
title: Add QUIC path-MTU discovery regression test
type: task
status: doing
area: testing
priority: high
owner: Lifecycle and PMTUD lane
parent: epic-protocol-conformance-tests
status_detail: Hysteria2 + TUIC landed; implement deterministic MASQUE/H3 PMTUD success, black-hole, and recovery fixture
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-07-16
---

## Summary

Path-MTU shifts (carrier handover, VPN nesting, jumbo-frame paths) break QUIC connections quietly. Add a regression test that simulates a mid-connection MTU drop and asserts the QUIC stack recovers.

## Context

Hysteria 2, TUIC, and MASQUE all run over Quinn. Quinn's PMTUD behaviour is configurable but easy to misconfigure. A small deliberate MTU drop in a loopback harness should be a recoverable event, not a connection kill.

## Acceptance criteria

- [x] A shared `quic_mtu_test_util` (under a `dev-dependencies` crate or a `tests/common/`) injects an MTU drop on a loopback UDP socket. **Done:** new `native/rust/crates/quic-mtu-test-util` crate exposes `MtuDropSocket` — a `quinn::AsyncUdpSocket` wrapper that silently drops QUIC 1-RTT (short-header) datagrams above a runtime-adjustable `MtuThreshold`, while always forwarding long-header (handshake) packets. GSO/GRO disabled for unambiguous per-datagram sizing; modeled on the in-repo `SalamanderUdpSocket`.
- [~] Each of Hysteria 2, TUIC, and MASQUE has one regression test asserting connection survival and payload integrity after the drop. **Hysteria 2 + TUIC done** (`ripdpi-bench/tests/quic_pmtud.rs`): each drives the real client against its loopback fixture (extended with `start_with_socket`), warms up to validate a high path MTU, lowers the threshold mid-connection, then asserts a 512 KiB round-trip survives intact within a timeout. **MASQUE deferred:** its only loopback fixture (`MasqueH2ConnectUdpFixture`) is H2-CONNECT over TCP — no QUIC datapath, so PMTUD does not apply; a quinn/H3 MASQUE fixture is net-new work tracked here.
- [x] The test runs in CI's standard test lane (not nightly). Both protocol tests and the two `quic-mtu-test-util` unit tests run under `cargo nextest run --workspace` (`scripts/ci/run-rust-workspace-tests.sh`).

## Definition of done

- [x] A Quinn configuration regression that disables PMTUD fails a named test. **Reframed to the physically-achievable teeth and documented in-code:** a QUIC connection **cannot be killed** by dropping only oversized datagrams — RFC 9000 guarantees a 1200-byte base MTU that always passes, and quinn's black-hole detector lowers the path MTU to that base on sustained loss *even when `mtu_discovery_config` is `None`* (only upward re-probing is gated). The real, observable PMTUD regression is **discovery**: `quic-mtu-test-util`'s `pmtud_enabled_discovers_larger_path_mtu_than_disabled` asserts that on a clear loopback path discovery validates a path MTU (~1452) strictly larger than the disabled base (1200); dropping `mtu_discovery_config(Some(..))` collapses it to the base and fails the test. `mtu_drop_socket_injects_recoverable_cliff` separately proves the fault injector caps the path MTU below the unconstrained path while the transfer survives.

## Links

- Port-hopping window soak test for Hysteria2 — closed task (shipped in commit `d8b962ea4`; git history is the audit trail)

## Work log

- 2026-07-16: Reassigned to the lifecycle/PMTUD lane. Remaining scope is a real Quinn/H3 MASQUE fixture with controlled MTU/PTB-equivalent signals, boundary payloads, black-hole/recovery telemetry, and payload-integrity assertions for IPv4/applicable IPv6.

- 2026-06-05: No `quic_mtu_test_util` crate or MTU test exists; no mtu/pmtud references in ripdpi-hysteria2, ripdpi-tuic, or ripdpi-masque; all acceptance criteria unmet — work not started.
- 2026-06-11: Landed the `quic-mtu-test-util` crate (`MtuDropSocket` + `MtuThreshold`) and added `start_with_socket(Arc<dyn quinn::AsyncUdpSocket>)` to `Hysteria2Loopback` / `TuicLoopback` (refactored `build_server_endpoint` → `build_server_config` + a shared `spawn`; `start()` behavior unchanged). Hysteria 2 + TUIC mid-connection-MTU-drop survival tests in `ripdpi-bench/tests/quic_pmtud.rs` pass; two deterministic `quic-mtu-test-util` tests pass (discovery-teeth + fault-injection cliff). Empirically established (with quinn 0.11.9 source + instrumentation) that QUIC survives any size-based drop via the 1200 base MTU + always-on black-hole detection, so the DoD was reframed to the discovery-observability teeth (documented in the crate + criteria above). MASQUE deferred — needs a quinn/H3 loopback fixture (existing fixture is H2-CONNECT/TCP, PMTUD-irrelevant). `cargo fmt --check` + `cargo clippy -- -D warnings` clean on the touched crates; existing fixture consumers (hysteria2/tuic loopback e2e, protocol-throughput bench) still compile and pass.
