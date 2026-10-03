## Purpose

Make observed diagnostic advice actionable and selector choices durable, lifecycle-safe and based on candidate transport payload evidence.

## ADDED Requirements

### Requirement: REQ-ADS-ACTIONS — User-directed diagnostic actions

Recommendations MUST expose a typed supported destination only when their evidence and remedy match. Rendering and navigating MUST NOT mutate settings or restart runtime. Unsupported automatic fixes MUST remain absent.

#### Scenario: Supported recommendation
- **WHEN** a user taps an actionable recommendation
- **THEN** the app opens its intended settings or diagnostics destination and preserves the detection-toolbar settings destination.

#### Scenario: Informational or insufficient evidence
- **WHEN** evidence cannot establish an in-app remedy
- **THEN** the advice remains informational and no universal automatic fix is offered.

### Requirement: REQ-ADS-SELECTION — Durable selector activation

Selector choices MUST be reconciled before runtime start and active switches MUST use service-owned session coordination. A switch MUST NOT cancel its own completion by destroying the observing VPN service. Failed or stale activation MUST NOT be reported as applied.

#### Scenario: Offline manual choice
- **WHEN** a user chooses a member while the runtime is stopped and later starts it
- **THEN** the selected member is activated before traffic starts, preserving manual provenance.

#### Scenario: Active switch
- **WHEN** a fresh selection changes during an active VPN session
- **THEN** session control applies it without a self-cancelling outer service stop/start and retains fail-closed behavior during reconfiguration.

### Requirement: REQ-ADS-PAYLOAD — Scoped candidate transport evidence

Automatic selection MUST validate bounded payload transfer through the actual candidate transport and configured probe plan, not infer working transport from a server TCP connection. Evidence MUST be rejected after profile configuration, network scope, or selection revision changes. Cloudflare members MUST remain manual-only even after successful probes.

#### Scenario: TCP success with broken transport
- **WHEN** a server accepts TCP but its relay handshake or payload response fails, stalls or is incomplete
- **THEN** that candidate is not eligible as a verified working transport.

#### Scenario: Fresh working candidate
- **WHEN** a non-Cloudflare candidate completes the configured transport and bounded response validation in the current scope
- **THEN** its measured latency can participate in the existing tolerance/CAS decision.

#### Scenario: Stale result or manual Cloudflare choice
- **WHEN** a probe completes after scope/configuration/revision changes or the user has manually selected Cloudflare
- **THEN** the late result cannot override that choice or promote Cloudflare automatically.

### Requirement: REQ-ADS-LOCALITY — Canonical interfaces and preserved safeguards

The app MUST use canonical recommendation, diagnostics and native interfaces; obsolete selector models, empty fix shells, compatibility aliases and unused inventory modules MUST be removed. Existing redaction, bootstrap recovery, VPN socket protection and architecture safeguards MUST remain enforced.

#### Scenario: Updated consumers
- **WHEN** removed interfaces are no longer available
- **THEN** all repository consumers compile against their canonical owners and relevant behavior, privacy and architecture regressions remain covered.
