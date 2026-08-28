# add-xray-provider-regression-matrix Specification

## Purpose
Define the observable completion contract for Add Xray provider regression matrix. Add focused automated coverage for the first Xray provider integration.

## Requirements

### Requirement: REQ-OUT-1786264762917829-001 — Config golden tests cover VLESS/REALITY, XHTTP, invalid combinations, and redaction

The regression matrix MUST include config-rendering coverage for supported VLESS/REALITY and VLESS/XHTTP profiles, rejected invalid combinations, and redacted diagnostic output.

#### Scenario: Config regression lane

- **WHEN** the config regression lane runs
- **THEN** it MUST compare rendered configs for the supported profile shapes against stable fixtures
- **AND** it MUST fail when unsupported combinations are accepted or when redacted output contains profile secrets or live endpoints

### Requirement: REQ-OUT-1786264762917829-002 — Service tests cover Xray startup failure, readiness timeout, stop, restart, and handover behavior

The service regression matrix MUST cover the lifecycle edges that can change Xray provider ownership or tunnel forwarding.

#### Scenario: Lifecycle regression lane

- **WHEN** the service lifecycle tests run
- **THEN** they MUST exercise Xray startup failure, listener readiness timeout, clean stop, restart, failed replacement, and handover outcomes
- **AND** each failure scenario MUST assert the resulting provider/tunnel ownership instead of only asserting a reported status

### Requirement: REQ-OUT-1786264762917829-003 — Protect-fd tests prove Xray dialer/listener sockets use the Android VPN protection path

The regression matrix MUST prove that non-loopback Xray sockets are protected before outbound use and that protection denial fails safely.

#### Scenario: Protect-fd regression lane

- **WHEN** the protect-fd contract tests simulate Xray socket creation
- **THEN** every non-loopback dialer or listener socket MUST be offered to the Android VPN protection path before connect or outbound use
- **AND** a denied protection result MUST close the socket and surface a provider failure without using the unprotected descriptor

### Requirement: REQ-OUT-1786264762917829-004 — DNS-loop regression proves provider bootstrap DNS does not re-enter TUN

The regression matrix MUST cover the Xray DNS ownership rules so provider DNS cannot recursively enter the VPN tunnel.

#### Scenario: DNS-loop regression lane

- **WHEN** the DNS ownership tests exercise the bridged Xray topology
- **THEN** provider hostname bootstrap MUST use an eligible underlying network without re-entering the TUN; client DNS handling MUST remain tunnel-owned
- **AND** missing eligible bootstrap authority or unsupported split-DNS or direct-TUN configurations MUST fail closed instead of silently routing provider DNS through the TUN

### Requirement: REQ-OUT-1786264762917829-005 — Device/emulator smoke test verifies active VPN traffic exits through the Xray outbound path

The regression matrix MUST define a device or emulator smoke lane that proves active VPN traffic is carried by the Xray outbound path.

#### Scenario: Device traffic smoke lane

- **WHEN** the device smoke lane starts RIPDPI VPN with Xray selected and a controlled Xray peer profile
- **THEN** traffic sent through the VPN MUST be observed by the controlled peer
- **AND** traffic to destinations outside the approved fixture routes MUST remain denied by the fixture rather than falling through to the public network

### Requirement: REQ-OUT-1786264762917829-006 — CI or documented manual lanes identify which Xray tests need network, emulator, or private fixture dependencies

The regression matrix MUST label each Xray validation lane by its required environment and external dependency boundary.

#### Scenario: Lane dependency map

- **WHEN** maintainers inspect the Xray regression matrix
- **THEN** each lane MUST state whether it is offline JVM/Go, linked libXray, emulator/device, live-network, or private-fixture dependent
- **AND** lanes that cannot run in ordinary local CI MUST name the manual or CI environment that can run them
