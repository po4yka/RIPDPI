---
task_id: DGN-1794734098746391
change: correct-dia07-quic-initial-probe
commit_sha: 4fc28312d29f78d949b7131384c18e924f51bed8
local: passed
local_evidence: "Locked Rust packet, transport, classification, runner and monitor suites passed. The pinned Quinn and Rustls loopback confirmed first-flight reply types, the full 3000-byte datagram, and Initial packet numbers 0 and 1. All 105 focused JVM tests, shared taxonomy checks in Rust and Kotlin, and just ci-gates passed."
remote_ci: required
remote_ci_evidence: Integration CI is pending.
device: not_applicable
device_evidence: Host loopback and JVM tests cover the changed probe and state decisions. External and physical-device acceptance is outside this correction.
artifact: not_applicable
artifact_evidence: This correction does not publish a binary artifact.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-QUIC-PROBE-REPLY | DGN-1794734226758622 | Runner unit tests and pinned Quinn loopback distinguished Initial, TLS close, and Retry. | Passed |
| REQ-QUIC-PROBE-AUTHORITY | DGN-1794734227385679 | Rust observation and offline classifier tests plus Kotlin capability and recommendation tests. | Passed |
| REQ-QUIC-PROBE-DATAGRAM | DGN-1794734227865118 | Direct and route IPv4/IPv6 transport regressions plus a 3000-byte Direct loopback. | Passed |
| REQ-QUIC-PROBE-FRAMES | DGN-1794734228345800 | Locked packet tests with independent Rustls and RFC vectors. | Passed |
| REQ-QUIC-PROBE-CANCEL | DGN-1794734228991845 | Transport cancel, timeout, and route tests plus monitor request validation. | Passed |
| REQ-QUIC-PROBE-PN | DGN-1794734229647891 | Rustls independently decoded packet numbers 0 and 1 from the live Direct loopback. | Passed |
| REQ-QUIC-PROBE-SOCKS | DGN-1794734230254826 | Transport tests for UDP association EOF, envelope filtering, and control deadline. | Passed |
| REQ-QUIC-PROBE-TARGET | DGN-1794734230729850 | Packet hostname tests, runner invalid-target tests, and Kotlin planner and admission tests. | Passed |

## Scope

The owning test commands were `cargo test --locked -p ripdpi-packets`, `cargo test --locked -p ripdpi-diagnostics-transport --lib`, the diagnostics runner QUIC unit and ClientHello integration tests, the monitor session validation and contract fixture tests, and focused JVM tests in settings, diagnostics, and the app. One pre-existing TCP transport test remains ignored. Physical Android, external QUIC paths, and native packaging were outside this correction.
