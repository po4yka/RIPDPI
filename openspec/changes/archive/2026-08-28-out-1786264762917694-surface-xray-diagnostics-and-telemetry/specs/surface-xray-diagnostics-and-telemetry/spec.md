## Purpose

Define the observable completion contract for Surface Xray diagnostics and telemetry. Expose Xray provider state in Home, Diagnostics, exports, and service telemetry.

## ADDED Requirements

### Requirement: REQ-OUT-1786264762917694-001 — Home connection stages identify Xray provider readiness and provider failures distinctly from tunnel failures

Home MUST surface Xray provider readiness and provider failures as a separate status axis from the VPN tunnel data plane.

#### Scenario: Provider-specific status rendering

- **WHEN** the active service telemetry includes an Xray provider snapshot
- **THEN** Home MUST render the provider stage using the Xray connection-stage model
- **AND** provider failures such as config invalid, listener bind failure, protect failure, DNS-loop suspicion, or outbound unreachable MUST not be collapsed into a generic tunnel failure

### Requirement: REQ-OUT-1786264762917694-002 — Diagnostics can run a provider-path check through the active Xray mode

Diagnostics MUST expose a user-triggered Xray provider check that reads the active provider's cached native-worker observations without launching fresh JNI work or performing a remote ping.

#### Scenario: Run provider diagnostics for active Xray session

- **WHEN** the user runs the Xray provider check while an Xray session is active
- **THEN** Diagnostics MUST report the cached version, listener-readiness, and wrapper-liveness observations for that active session
- **AND** the report MUST be discarded if the owning session, coordinator registration, or substantive provider snapshot changes before publication; timestamp-only refreshes MUST NOT invalidate it

#### Scenario: No active Xray provider

- **WHEN** the user runs or views the Xray provider check while no Xray session is active
- **THEN** Diagnostics MUST show that the provider check is not applicable instead of probing a stale or native runtime

### Requirement: REQ-OUT-1786264762917694-003 — Export/share summaries redact profile credentials and live endpoints

Exported and shared Xray provider summaries MUST be secret-safe and endpoint-safe.

#### Scenario: Redacted provider summary

- **WHEN** serialized diagnostic exports or share summaries include Xray provider status
- **THEN** the summary MUST include only safe metadata such as provider state, version, readiness, protocol kind, failure class, and redacted findings
- **AND** it MUST not include UUIDs, private keys, server addresses, SNI values, local listener endpoints, or other live endpoints

### Requirement: REQ-OUT-1786264762917694-004 — Xray API/stat probing is used only when enabled safely for the Android runtime topology

Diagnostics MUST treat Xray Stat API probing as not applicable for the Android in-process TUN topology unless a safe, explicitly configured child-process topology enables it.

#### Scenario: Stat API not applicable on Android TUN runtime

- **WHEN** Diagnostics builds a provider report for the Android in-process Xray runtime
- **THEN** the Stat API probe result MUST be reported as not applicable
- **AND** provider health MUST be derived from cached native-worker observations rather than from a fresh Stat API call

### Requirement: REQ-OUT-1786264762917694-005 — Regression fixtures cover provider healthy, config invalid, protect failure, DNS-loop suspected, and outbound unreachable states

Diagnostics regression fixtures MUST cover the provider states that drive Home, Diagnostics, and export presentation.

#### Scenario: Provider fixture coverage

- **WHEN** provider diagnostics presentation tests run
- **THEN** they MUST cover healthy provider, invalid config, protect failure, DNS-loop suspected, outbound unreachable, and stale-or-not-applicable states
- **AND** each fixture MUST remain free of raw credentials and live endpoints
