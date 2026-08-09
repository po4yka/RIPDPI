## Purpose

Define the observable completion contract for Run Xray as managed VPN relay runtime. Implement a supervised Xray runtime that starts, reports readiness, exposes health, and stops cleanly inside RIPDPI's Android service layer

## ADDED Requirements

### Requirement: REQ-OUT-1786264762917107-001 — Runtime registers libXray dialer/listener protection before starting Xray. — Ri…

The RIPDPI implementation MUST satisfy this portfolio criterion: Runtime registers libXray dialer/listener protection before starting Xray. — RipDpiXrayRuntime registers the protect controller with the bridge BEFORE start; protect-first ordering is asserted by RipDpiXrayRuntimeTest and XrayProtectFdContractTest.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Runtime registers libXray dialer/listener protection before starting Xray. — RipDpiXrayRuntime registers the protect controller with the bridge BEFORE start; protect-first ordering is asserted by RipDpiXrayRuntimeTest and XrayProtectFdContractTest

### Requirement: REQ-OUT-1786264762917107-002 — Startup waits for a concrete listener or verified Xray state before VPN tunnel…

The RIPDPI implementation MUST satisfy this portfolio criterion: Startup waits for a concrete listener or verified Xray state before VPN tunnel handoff. — readiness success/timeout covered in RipDpiXrayRuntimeTest.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Startup waits for a concrete listener or verified Xray state before VPN tunnel handoff. — readiness success/timeout covered in RipDpiXrayRuntimeTest

### Requirement: REQ-OUT-1786264762917107-003 — Stop path is bounded, idempotent, and reports typed clean/failed stop causes. —…

The RIPDPI implementation MUST satisfy this portfolio criterion: Stop path is bounded, idempotent, and reports typed clean/failed stop causes. — typed StopCause (Clean/AlreadyStopped/Failed), bounded via IO dispatcher; idempotent/late/hung-stop tests green.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Stop path is bounded, idempotent, and reports typed clean/failed stop causes. — typed StopCause (Clean/AlreadyStopped/Failed), bounded via IO dispatcher; idempotent/late/hung-stop tests green

### Requirement: REQ-OUT-1786264762917107-004 — Xray version and basic provider state flow into service telemetry without expos…

The RIPDPI implementation MUST satisfy this portfolio criterion: Xray version and basic provider state flow into service telemetry without exposing profile secrets. — pollTelemetry() emits a NativeRuntimeSnapshot with version+state and a secret-free assertion test.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Xray version and basic provider state flow into service telemetry without exposing profile secrets. — pollTelemetry() emits a NativeRuntimeSnapshot with version+state and a secret-free assertion test

### Requirement: REQ-OUT-1786264762917107-005 — Unit or service tests cover startup failure, invalid config, late stop, and cra…

The RIPDPI implementation MUST satisfy this portfolio criterion: Unit or service tests cover startup failure, invalid config, late stop, and crash/exit mapping. — 14 tests in RipDpiXrayRuntimeTest (green offline in :core:engine-api).

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Unit or service tests cover startup failure, invalid config, late stop, and crash/exit mapping. — 14 tests in RipDpiXrayRuntimeTest (green offline in :core:engine-api)
