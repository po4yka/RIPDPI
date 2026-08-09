## Purpose

Define the observable completion contract for Introduce a VPN-session Hilt scope to reset per-session service state. The 2026-06-10 Kotlin audit found Hilt has grown to 134 SingletonComponent modules (up from 71+) with no custom VPN-session scope. Several service-layer singletons logically belong to a VPN-session lifetime — ServiceStateStore, RootHelperManager, VpnAppExclusionPolicy, VpnDhtMitigationPolicy, NetworkFingerprintProvider — yet are @Singleton, so state accumulated in one session persists into the next unless explicitly cleared (e.g., a stale ServiceStateStore emitting previous-session telemetry to…

## ADDED Requirements

### Requirement: REQ-AND-1786264762917810-001 — PR enumerates which singletons moved to session scope and why each qualifies

The RIPDPI implementation MUST satisfy this portfolio criterion: PR enumerates which singletons moved to session scope and why each qualifies.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that PR enumerates which singletons moved to session scope and why each qualifies

### Requirement: REQ-AND-1786264762917810-002 — Migrated objects get a fresh instance per VPN session; old-session state is gon…

The RIPDPI implementation MUST satisfy this portfolio criterion: Migrated objects get a fresh instance per VPN session; old-session state is gone on restart.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Migrated objects get a fresh instance per VPN session; old-session state is gone on restart

### Requirement: REQ-AND-1786264762917810-003 — Session-restart test confirms no cross-session state bleed (e.g., telemetry obs…

The RIPDPI implementation MUST satisfy this portfolio criterion: Session-restart test confirms no cross-session state bleed (e.g., telemetry observers do not receive prior-session events).

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Session-restart test confirms no cross-session state bleed (e.g., telemetry observers do not receive prior-session events)

### Requirement: REQ-AND-1786264762917810-004 — /gradlew :core:service:testDebugUnitTest --locked green; no Hilt graph errors

The RIPDPI implementation MUST satisfy this portfolio criterion: /gradlew :core:service:testDebugUnitTest --locked green; no Hilt graph errors.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that /gradlew :core:service:testDebugUnitTest --locked green; no Hilt graph errors
