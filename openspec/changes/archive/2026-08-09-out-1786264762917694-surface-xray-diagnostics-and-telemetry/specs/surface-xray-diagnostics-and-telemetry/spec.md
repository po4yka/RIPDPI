## Purpose

Define the observable completion contract for Surface Xray diagnostics and telemetry. Expose Xray provider state in Home, Diagnostics, exports, and service telemetry

## ADDED Requirements

### Requirement: REQ-OUT-1786264762917694-001 — Home connection stages identify Xray provider readiness and provider failures d…

The RIPDPI implementation MUST satisfy this portfolio criterion: Home connection stages identify Xray provider readiness and provider failures distinctly from tunnel failures. — XrayConnectionStage (Validating → StartingEngine → ListenerReady → ProbingOutbound → Connected, with a ProviderFailed branch) plus XrayProviderFai….

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Home connection stages identify Xray provider readiness and provider failures distinctly from tunnel failures. — XrayConnectionStage (Validating → StartingEngine → ListenerReady → ProbingOutbound → Connected, with a ProviderFailed branch) plus XrayProviderFai…

### Requirement: REQ-OUT-1786264762917694-002 — Diagnostics can run a provider-path check through the active Xray mode (wired +…

The RIPDPI implementation MUST satisfy this portfolio criterion: Diagnostics can run a provider-path check through the active Xray mode (wired + CI-tested with fakes; live device run still OPEN). — XrayProviderDiagnosticsProbeRunner (:core:service) runs Version + ListenerReadiness + WrapperPing in-process against the ACTIV….

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Diagnostics can run a provider-path check through the active Xray mode (wired + CI-tested with fakes; live device run still OPEN). — XrayProviderDiagnosticsProbeRunner (:core:service) runs Version + ListenerReadiness + WrapperPing in-process against the ACTIV…

### Requirement: REQ-OUT-1786264762917694-003 — Export/share summaries redact profile credentials and live endpoints. — XrayPro…

The RIPDPI implementation MUST satisfy this portfolio criterion: Export/share summaries redact profile credentials and live endpoints. — XrayProviderTelemetrySummaries routes every endpoint/secret through XrayProfileRedactor; verified by XrayProviderDiagnosticsTest (offline).

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Export/share summaries redact profile credentials and live endpoints. — XrayProviderTelemetrySummaries routes every endpoint/secret through XrayProfileRedactor; verified by XrayProviderDiagnosticsTest (offline)

### Requirement: REQ-OUT-1786264762917694-004 — Xray API/stat probing is used only when enabled safely for the Android runtime…

The RIPDPI implementation MUST satisfy this portfolio criterion: Xray API/stat probing is used only when enabled safely for the Android runtime topology. — StatApi probe kind is typed and flagged child-process-only (never in-process for the Android TUN topology); the safe set is Version/WrapperPing/ListenerReadiness. Type-….

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Xray API/stat probing is used only when enabled safely for the Android runtime topology. — StatApi probe kind is typed and flagged child-process-only (never in-process for the Android TUN topology); the safe set is Version/WrapperPing/ListenerReadiness. Type-…

### Requirement: REQ-OUT-1786264762917694-005 — Regression fixtures cover provider healthy, config invalid, protect failure, DN…

The RIPDPI implementation MUST satisfy this portfolio criterion: Regression fixtures cover provider healthy, config invalid, protect failure, DNS-loop suspected, and outbound unreachable states. — XrayProviderDiagnosticsFixtures (all five states) asserted by XrayProviderDiagnosticsTest (15 tests green offline).

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Regression fixtures cover provider healthy, config invalid, protect failure, DNS-loop suspected, and outbound unreachable states. — XrayProviderDiagnosticsFixtures (all five states) asserted by XrayProviderDiagnosticsTest (15 tests green offline)
