## Purpose

Define the observable completion contract for Epic - Protocol conformance and regression tests. Lock the on-wire correctness of RIPDPI's transport stack with golden-fixture conformance tests, cross-stack chain tests, throughput benchmarks, and protocol-behavior regression tests. These guard the wire contract layer; the completed orchestration-test epic remains available in git history

## ADDED Requirements

### Requirement: REQ-EPC-1786264762917455-001 — Every transport has at least eight upstream-pinned golden fixtures where a conf…

The RIPDPI implementation MUST satisfy this portfolio criterion: Every transport has at least eight upstream-pinned golden fixtures where a conformance bar applies, each round-tripped.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Every transport has at least eight upstream-pinned golden fixtures where a conformance bar applies, each round-tripped

### Requirement: REQ-EPC-1786264762917455-002 — The cross-stack chain test covers single-stream and VLESS Reality mux/multi-str…

The RIPDPI implementation MUST satisfy this portfolio criterion: The cross-stack chain test covers single-stream and VLESS Reality mux/multi-stream behavior.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that The cross-stack chain test covers single-stream and VLESS Reality mux/multi-stream behavior

### Requirement: REQ-EPC-1786264762917455-003 — Throughput baselines for all seven transports are captured in scripts/ci/rust-b…

The RIPDPI implementation MUST satisfy this portfolio criterion: Throughput baselines for all seven transports are captured in scripts/ci/rust-bench-baseline.json and enforced. Done 2026-06-11 (reference-runner capture; nightly enforced lane armed).

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Throughput baselines for all seven transports are captured in scripts/ci/rust-bench-baseline.json and enforced. Done 2026-06-11 (reference-runner capture; nightly enforced lane armed)

### Requirement: REQ-EPC-1786264762917455-004 — A deliberate framing/behavior regression in any covered layer fails a named tes…

The RIPDPI implementation MUST satisfy this portfolio criterion: A deliberate framing/behavior regression in any covered layer fails a named test. Partial: VLESS mux, QUIC PMTUD, and throughput gates contribute; full coverage still awaits Salamander fixtures.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that A deliberate framing/behavior regression in any covered layer fails a named test. Partial: VLESS mux, QUIC PMTUD, and throughput gates contribute; full coverage still awaits Salamander fixtures
