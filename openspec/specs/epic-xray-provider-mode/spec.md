# epic-xray-provider-mode Specification

## Purpose
Define the observable contract for selecting Xray as an Android VPN provider, with VLESS/REALITY TCP and XHTTP profiles, managed runtime ownership, and redacted diagnostics.

## Requirements

### Requirement: REQ-EPC-1786264762917329-001 — Start Android VPN with Xray

RIPDPI MUST support Xray as the active Android VPN provider using the embedded libXray runtime and the TUN-to-local-inbound topology.

#### Scenario: Start a selected provider

- **WHEN** a user saves a supported, validated Xray profile, selects Xray, and starts Android VPN mode
- **THEN** the managed libXray runtime MUST start and TUN traffic MUST pass through its local inbound to the configured outbound

### Requirement: REQ-EPC-1786264762917329-002 — Validate and render supported profiles

RIPDPI MUST validate and render supported VLESS/REALITY TCP and XHTTP profiles. Unsupported or invalid input MUST be rejected, and serialized diagnostic exports MUST NOT expose profile credentials or private endpoint values.

#### Scenario: Import and render a profile

- **WHEN** a user imports a supported profile through the URI or JSON input
- **THEN** validation MUST preserve its supported fields in the rendered Xray configuration and MUST reject unknown, malformed, or unsupported fields instead of silently dropping them

#### Scenario: Export provider diagnostics

- **WHEN** provider state or a configuration failure is included in a serialized diagnostic export
- **THEN** the result MUST contain only allowed typed diagnostic fields without profile credentials, endpoint values, or untrusted free-form details

### Requirement: REQ-EPC-1786264762917329-003 — Protect sockets and preserve DNS ownership

Socket protection MUST be installed before Xray opens outbound sockets. The local inbound MUST remain loopback-only. Tunnel DNS and provider bootstrap MUST retain their assigned tunnel and underlying-network ownership without routing provider traffic back into VPN capture.

#### Scenario: Start and route provider traffic

- **WHEN** the Xray provider starts under an active Android VPN
- **THEN** outbound sockets MUST be protected before use, tunnel DNS MUST use the configured tunnel route, and provider bootstrap MUST use the underlying network

### Requirement: REQ-EPC-1786264762917329-004 — Present typed provider state

Home, Diagnostics, and Settings MUST display typed Xray provider state and distinguish provider failures from tunnel failures. Stale probe results MUST NOT replace the current session state.

#### Scenario: Replace a provider session during a probe

- **WHEN** a probe completes after its provider session has been stopped or replaced
- **THEN** the result MUST be discarded and the UI MUST retain the current session's typed state

### Requirement: REQ-EPC-1786264762917329-005 — Verify the internal build

Lifecycle, configuration, socket-protection, and telemetry tests MUST cover the provider integration. Android smoke tests MUST exercise both supported transports through the real TUN, including owned TCP and plain UDP DNS traffic, stop/start, and rejection of direct fallback with an invalid identity.

#### Scenario: Exercise an owned peer through Android VPN

- **WHEN** the Android smoke suite runs with the real embedded runtime and an independent owned peer
- **THEN** both transports MUST deliver the expected TCP and DNS receipts through the TUN, stop/start MUST succeed, and an invalid identity MUST produce no successful application response or owned-service receipt and MUST NOT reach the direct fallback sentinel while VPN capture is active
