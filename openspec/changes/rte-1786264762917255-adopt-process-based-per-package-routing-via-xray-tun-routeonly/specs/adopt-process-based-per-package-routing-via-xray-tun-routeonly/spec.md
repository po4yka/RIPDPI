## Purpose

Define the observable completion contract for Adopt process-based per-package routing via Xray TUN routeOnly. reference Android implementation 2.1.0 (2026-04-17) shipped per-package routing via Xray TUN with routeOnly enabled. Adopt the same pattern so RIPDPI users can route selected platform-detection-positive apps directly while everything else goes through VLESS

## ADDED Requirements

### Requirement: REQ-RTE-1786264762917255-001 — Per-package routing enforces exclusions via VpnAppExclusionPolicy using VpnServ…

The RIPDPI implementation MUST satisfy this portfolio criterion: Per-package routing enforces exclusions via VpnAppExclusionPolicy using VpnService.Builder addAllowedApplication/addDisallowedApplication (implemented; note: routeOnly Xray TUN pattern from the task title was not adopted — RIPDPI uses the equivalent Android-n….

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Per-package routing enforces exclusions via VpnAppExclusionPolicy using VpnService.Builder addAllowedApplication/addDisallowedApplication (implemented; note: routeOnly Xray TUN pattern from the task title was not adopted — RIPDPI uses the equivalent Android-n…

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

### Requirement: REQ-RTE-1786264762917255-004 — Per-package egress separation is proven on device

The implementation MUST demonstrate on a real device that a blocklisted app uses direct egress while an allowed app uses the configured tunneled egress.

#### Scenario: Verify egress separation

- **WHEN** a blocklisted app and an allowed app each perform the same network request on the test device
- **THEN** their observed egress addresses MUST match the direct and configured tunneled paths respectively
