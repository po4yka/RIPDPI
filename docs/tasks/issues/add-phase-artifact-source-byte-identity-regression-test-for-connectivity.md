---
title: Add phase/artifact-source byte-identity regression test for connectivity stage runners
type: task
status: doing
area: testing
priority: high
owner: Test Automation Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Add phase/artifact-source byte-identity regression test for connectivity stage runners #repo/RIPDPI #area/testing #status/doing ⏫

## Objective
Add a regression test in `ripdpi-monitor-engine` asserting that every `ExecutionStageRunner::phase()` and `ConnectivityProbeFamily::ARTIFACT_SOURCE` constant is byte-identical to the pre-split list. This is gate G1 of POY-12.

## Context
POY-7 noted that the `Web` stage publishes phase string `reachability` (not `web`). The connectivity decomposition (`c795e066..af66236c`) preserves all phase/artifact strings, but there is no test that locks them in. A future contributor renaming a runner could silently desync the phase string and break downstream telemetry consumers.

## Acceptance criteria
- Test asserts the following pairs are byte-identical to a frozen const slice in the test module:
- dns / dns_integrity
- tcp / tcp_fat_header
- quic / quic_reachability
- reachability / domain_reachability   (web runner — note phase is `reachability`, not `web`)
- throughput / throughput_window
- circumvention / circumvention_reachability
- service / service_reachability
- telegram / telegram
- environment / network_environment
- Test lives in `native/rust/crates/ripdpi-monitor-engine/src/engine/runners` (sibling of the `connectivity/` module) or in `tests/` if accessing `pub(super)` symbols requires it.
- Test references the runner constants directly (no string duplication beyond the frozen list), so a rename forces an explicit fixture update.

## Required verification
- `cargo nextest run -p ripdpi-monitor-engine -E 'test(phase) or test(artifact_source)'` green.
- Mutating any one phase or artifact-source string in source must make the test fail.

## Risks
None. Pure regression net for a string-identity invariant.

## Definition of done
- Test added, green locally and in CI.
- POY-12 gate G1 marked satisfied.
