## Purpose

Define the observable completion contract for Add Xray profile UX and import flow. Add the user-facing flow for selecting Xray VPN mode and importing or editing initial Xray profiles

## ADDED Requirements

### Requirement: REQ-OUT-1786264762917619-001 — Mode Editor can select Xray-backed VPN mode separately from native RIPDPI direc…

The RIPDPI implementation MUST satisfy this portfolio criterion: Mode Editor can select Xray-backed VPN mode separately from native RIPDPI direct/proxy modes. — XrayServiceModeOption (:core:data:runtime-state) flattens provider×mode into the mutually-exclusive picker set; XrayProviderSelection (:app) records the choice and….

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Mode Editor can select Xray-backed VPN mode separately from native RIPDPI direct/proxy modes. — XrayServiceModeOption (:core:data:runtime-state) flattens provider×mode into the mutually-exclusive picker set; XrayProviderSelection (:app) records the choice and…

### Requirement: REQ-OUT-1786264762917619-002 — Import supports at least the first approved share/config shapes and fails close…

The RIPDPI implementation MUST satisfy this portfolio criterion: Import supports at least the first approved share/config shapes and fails closed on unsupported or unsafe fields. — XrayImportParser (:core:data:catalog) parses vless:// REALITY/XHTTP links and raw config JSON, rejecting unsupported transports, missing fields….

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Import supports at least the first approved share/config shapes and fails closed on unsupported or unsafe fields. — XrayImportParser (:core:data:catalog) parses vless:// REALITY/XHTTP links and raw config JSON, rejecting unsupported transports, missing fields…

### Requirement: REQ-OUT-1786264762917619-003 — Validation errors are actionable but redact credentials and endpoints. — import…

The RIPDPI implementation MUST satisfy this portfolio criterion: Validation errors are actionable but redact credentials and endpoints. — import errors return REDACTED, jargon-free messages; verified by XrayImportParserTest (offline) and the redaction regression suite.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Validation errors are actionable but redact credentials and endpoints. — import errors return REDACTED, jargon-free messages; verified by XrayImportParserTest (offline) and the redaction regression suite

### Requirement: REQ-OUT-1786264762917619-004 — Onboarding can validate an Xray profile as the chosen mode before finish. — the…

The RIPDPI implementation MUST satisfy this portfolio criterion: Onboarding can validate an Xray profile as the chosen mode before finish. — the reusable validation surface (XrayProfileImportViewModel, XrayCapability) exists and is wired for onboarding reuse, but the onboarding-to-finish flow is exercised only by :app test….

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Onboarding can validate an Xray profile as the chosen mode before finish. — the reusable validation surface (XrayProfileImportViewModel, XrayCapability) exists and is wired for onboarding reuse, but the onboarding-to-finish flow is exercised only by :app test…

### Requirement: REQ-OUT-1786264762917619-005 — Compose/UI tests cover selection, validation failure, and successful imported-p…

The RIPDPI implementation MUST satisfy this portfolio criterion: Compose/UI tests cover selection, validation failure, and successful imported-profile state. — XrayProfileImportScreenTest / XrayProfileImportViewModelTest are authored and were exercised to green during development, but the final :app:testGithubDebugUnitTest….

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Compose/UI tests cover selection, validation failure, and successful imported-profile state. — XrayProfileImportScreenTest / XrayProfileImportViewModelTest are authored and were exercised to green during development, but the final :app:testGithubDebugUnitTest…
