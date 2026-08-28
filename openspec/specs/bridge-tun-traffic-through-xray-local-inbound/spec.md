# bridge-tun-traffic-through-xray-local-inbound Specification

## Purpose
Define the observable completion contract for Bridge TUN traffic through Xray local inbound. Route Android VPN TUN traffic through Xray's local inbound for the first Xray tunneled outbound profile milestone.

## Requirements

### Requirement: REQ-OUT-1786264762917422-001 — VPN startup can select Xray as the tunnel's upstream local endpoint

VPN startup MUST route the managed tunnel to the Xray local inbound endpoint when Xray is the selected provider, and MUST retain the native endpoint when the native provider is selected.

#### Scenario: Xray provider handoff

- **WHEN** the durable provider selection is Xray and an accepted Xray profile resolves to a local inbound endpoint
- **THEN** the VPN tunnel MUST use that loopback endpoint as its upstream instead of the native RIPDPI proxy endpoint
- **AND** the selected route metadata MUST identify Xray as the active provider

### Requirement: REQ-OUT-1786264762917422-002 — Xray outbound sockets and DNS are protected so provider traffic does not loop into the TUN fd

The Xray provider path MUST protect outbound sockets and DNS activity from being routed back into the VPN TUN device.

#### Scenario: Protected provider egress

- **WHEN** Xray opens a non-loopback outbound socket or performs provider-owned DNS work while the VPN protection callback is active
- **THEN** the descriptor MUST be protected before outbound use
- **AND** protection failure MUST abort provider startup or the affected operation without handing TUN traffic to an unsafe provider

### Requirement: REQ-OUT-1786264762917422-003 — Existing tunnel telemetry remains available when the upstream endpoint is Xray instead of RIPDPI-native proxy

Tunnel telemetry MUST continue to report tunnel lifecycle and packet-forwarding state when the upstream endpoint is Xray.

#### Scenario: Telemetry with Xray upstream

- **WHEN** the managed tunnel is running with an Xray local inbound upstream
- **THEN** existing tunnel telemetry MUST continue to publish tunnel status, route metadata, and counters
- **AND** provider-specific telemetry MUST be additive rather than replacing the tunnel data-plane telemetry

### Requirement: REQ-OUT-1786264762917422-004 — Network handover restarts both Xray and tunnel when the local inbound or provider route changes

Network handover MUST replace the provider and tunnel transactionally when a route change requires a new Xray local inbound or provider policy.

#### Scenario: Route-changing handover

- **WHEN** network handover changes the Xray local inbound endpoint, provider configuration, or route policy
- **THEN** the old tunnel forwarding path MUST be quiesced before the old Xray runtime is released
- **AND** the new Xray runtime and tunnel MUST become active together, or the previous TUN ownership barrier MUST be retained for explicit stop or retry

### Requirement: REQ-OUT-1786264762917422-005 — A local/device smoke test proves traffic exits through the Xray outbound

A device or local integration smoke test MUST prove that traffic entering the RIPDPI VPN exits through the configured Xray outbound rather than through the native provider or public fallback.

#### Scenario: Xray outbound smoke

- **WHEN** RIPDPI VPN is started with Xray selected and a controlled peer profile
- **THEN** traffic sent through the VPN to an approved fixture destination MUST be received by the controlled Xray peer
- **AND** the smoke evidence MUST distinguish Xray peer receipt from direct host reachability
