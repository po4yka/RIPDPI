## Purpose

Define the Android app routing policy used when the VPN uses the Xray relay.

## ADDED Requirements

### Requirement: REQ-RTE-1786264762917255-001 — Apply per-app routing through Android VPN

RIPDPI MUST apply its app routing policy with `VpnService.Builder` allowed and disallowed application lists.

#### Scenario: Configure the VPN app filter

- **WHEN** the user configures an app allowlist or blocklist and starts the VPN
- **THEN** the Android VPN builder receives the corresponding filter

### Requirement: REQ-RTE-1786264762917255-002 — UI exposes per-package allowlist (route through tunnel) and blocklist (route di…

The RIPDPI implementation MUST satisfy this portfolio criterion: UI exposes per-package allowlist (route through tunnel) and blocklist (route direct).

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that UI exposes per-package allowlist (route through tunnel) and blocklist (route direct)

### Requirement: REQ-RTE-1786264762917255-003 — Default blocklist seeds with known platform-detection-positive apps

The RIPDPI implementation MUST satisfy this portfolio criterion: Default blocklist seeds with known platform-detection-positive apps per platform-vpn-detection-april-2026.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Default blocklist seeds with known platform-detection-positive apps per platform-vpn-detection-april-2026

### Requirement: REQ-RTE-1786264762917255-004 — Per-package routing plan selects the configured path

The Android routing policy MUST exclude blocklisted installed apps from the VPN. Other apps remain subject to the VPN policy.

#### Scenario: Compute the routing plan

- **WHEN** the routing policy builds its plan with a blocklisted installed app and another installed app
- **THEN** it excludes the blocklisted app and keeps the other app subject to the VPN policy
