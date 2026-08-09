## Purpose

Define the observable completion contract for Bridge TUN traffic through Xray local inbound. Route Android VPN TUN traffic through Xray's local inbound for the first Xray tunneled outbound profile milestone

## ADDED Requirements

### Requirement: REQ-OUT-1786264762917422-001 — VPN startup can select Xray as the tunnel's upstream local endpoint. — XrayTunn…

The RIPDPI implementation MUST satisfy this portfolio criterion: VPN startup can select Xray as the tunnel's upstream local endpoint. — XrayTunnelHandoff resolves the upstream from VpnProviderKind (Native keeps tun2socks; Xray points the tunnel at 127.0.0.1:localInboundPort); covered by XrayTunnelHandoffTest and XrayProvid….

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that VPN startup can select Xray as the tunnel's upstream local endpoint. — XrayTunnelHandoff resolves the upstream from VpnProviderKind (Native keeps tun2socks; Xray points the tunnel at 127.0.0.1:localInboundPort); covered by XrayTunnelHandoffTest and XrayProvid…

### Requirement: REQ-OUT-1786264762917422-002 — Xray outbound sockets and DNS are protected so provider traffic does not loop i…

The RIPDPI implementation MUST satisfy this portfolio criterion: Xray outbound sockets and DNS are protected so provider traffic does not loop into the TUN fd. — protect-first ordering in RipDpiXrayRuntime; DNS ownership pinned to the tunnel; proven by XrayProtectFdContractTest and XrayDnsLoopRegressionTest.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Xray outbound sockets and DNS are protected so provider traffic does not loop into the TUN fd. — protect-first ordering in RipDpiXrayRuntime; DNS ownership pinned to the tunnel; proven by XrayProtectFdContractTest and XrayDnsLoopRegressionTest

### Requirement: REQ-OUT-1786264762917422-003 — Existing tunnel telemetry remains available when the upstream endpoint is Xray…

The RIPDPI implementation MUST satisfy this portfolio criterion: Existing tunnel telemetry remains available when the upstream endpoint is Xray instead of RIPDPI-native proxy. — XrayProviderOrchestrator drives the ManagedTunnel seam unchanged; orchestrator tests assert the tunnel lifecycle is preserved when upstream is Xray.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Existing tunnel telemetry remains available when the upstream endpoint is Xray instead of RIPDPI-native proxy. — XrayProviderOrchestrator drives the ManagedTunnel seam unchanged; orchestrator tests assert the tunnel lifecycle is preserved when upstream is Xray

### Requirement: REQ-OUT-1786264762917422-004 — Network handover restarts both Xray and tunnel when the local inbound or provid…

The RIPDPI implementation MUST satisfy this portfolio criterion: Network handover restarts both Xray and tunnel when the local inbound or provider route changes. — route-change dual-restart (tunnel stopped before Xray) covered by XrayProviderOrchestratorTest / XrayServiceLifecycleMatrixTest.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Network handover restarts both Xray and tunnel when the local inbound or provider route changes. — route-change dual-restart (tunnel stopped before Xray) covered by XrayProviderOrchestratorTest / XrayServiceLifecycleMatrixTest

### Requirement: REQ-OUT-1786264762917422-005 — A local/device smoke test proves traffic exits through the Xray outbound. — doc…

The RIPDPI implementation MUST satisfy this portfolio criterion: A local/device smoke test proves traffic exits through the Xray outbound. — documented in docs/contributor/xray-tun-bridge-smoke.md but UNVERIFIED IN CI. OPEN: requires gomobile/libXray + NDK29 native engine + device + live server; the smoke lane cannot run o….

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that A local/device smoke test proves traffic exits through the Xray outbound. — documented in docs/contributor/xray-tun-bridge-smoke.md but UNVERIFIED IN CI. OPEN: requires gomobile/libXray + NDK29 native engine + device + live server; the smoke lane cannot run o…
