## Purpose

Define the observable completion contract for Add Xray provider regression matrix. Add focused automated coverage for the first Xray provider integration

## ADDED Requirements

### Requirement: REQ-OUT-1786264762917829-001 — Config golden tests cover VLESS/REALITY, XHTTP, invalid combinations, and redac…

The RIPDPI implementation MUST satisfy this portfolio criterion: Config golden tests cover VLESS/REALITY, XHTTP, invalid combinations, and redaction. — XrayConfigRendererTest, XrayProfileRedactorTest, XrayRedactionRegressionTest (:core:data:catalog, green offline).

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Config golden tests cover VLESS/REALITY, XHTTP, invalid combinations, and redaction. — XrayConfigRendererTest, XrayProfileRedactorTest, XrayRedactionRegressionTest (:core:data:catalog, green offline)

### Requirement: REQ-OUT-1786264762917829-002 — Service tests cover Xray startup failure, readiness timeout, stop, restart, and…

The RIPDPI implementation MUST satisfy this portfolio criterion: Service tests cover Xray startup failure, readiness timeout, stop, restart, and handover behavior. — XrayServiceLifecycleMatrixTest (one named test per edge) + RipDpiXrayRuntimeTest (:core:engine-api, green offline).

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Service tests cover Xray startup failure, readiness timeout, stop, restart, and handover behavior. — XrayServiceLifecycleMatrixTest (one named test per edge) + RipDpiXrayRuntimeTest (:core:engine-api, green offline)

### Requirement: REQ-OUT-1786264762917829-003 — Protect-fd tests prove Xray dialer/listener sockets use the Android VPN protect…

The RIPDPI implementation MUST satisfy this portfolio criterion: Protect-fd tests prove Xray dialer/listener sockets use the Android VPN protection path. — XrayProtectFdContractTest: a socket-simulating fake bridge asserts protect strictly precedes connect, a denied protect aborts the socket, and the loopback inbound is ne….

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Protect-fd tests prove Xray dialer/listener sockets use the Android VPN protection path. — XrayProtectFdContractTest: a socket-simulating fake bridge asserts protect strictly precedes connect, a denied protect aborts the socket, and the loopback inbound is ne…

### Requirement: REQ-OUT-1786264762917829-004 — DNS-loop regression proves provider bootstrap DNS does not re-enter TUN. — Xray…

The RIPDPI implementation MUST satisfy this portfolio criterion: DNS-loop regression proves provider bootstrap DNS does not re-enter TUN. — XrayDnsLoopRegressionTest: DNS ownership pinned to the tunnel, split XrayDns not constructible for the bridged topology, SetTunFd topology refused (green offline).

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that DNS-loop regression proves provider bootstrap DNS does not re-enter TUN. — XrayDnsLoopRegressionTest: DNS ownership pinned to the tunnel, split XrayDns not constructible for the bridged topology, SetTunFd topology refused (green offline)

### Requirement: REQ-OUT-1786264762917829-005 — Device/emulator smoke test verifies active VPN traffic exits through the Xray o…

The RIPDPI implementation MUST satisfy this portfolio criterion: Device/emulator smoke test verifies active VPN traffic exits through the Xray outbound path. — documented in docs/contributor/xray-tun-bridge-smoke.md / xray-regression-matrix.md but UNVERIFIED IN CI. OPEN: requires gomobile/libXray + NDK29 native + device/em….

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Device/emulator smoke test verifies active VPN traffic exits through the Xray outbound path. — documented in docs/contributor/xray-tun-bridge-smoke.md / xray-regression-matrix.md but UNVERIFIED IN CI. OPEN: requires gomobile/libXray + NDK29 native + device/em…

### Requirement: REQ-OUT-1786264762917829-006 — CI or documented manual lanes identify which Xray tests need network, emulator,…

The RIPDPI implementation MUST satisfy this portfolio criterion: CI or documented manual lanes identify which Xray tests need network, emulator, or private fixture dependencies. — docs/contributor/xray-regression-matrix.md indexes the whole surface and splits CI-offline lanes from device/emulator, live-network, and private….

#### Scenario: Verify criterion 6

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that CI or documented manual lanes identify which Xray tests need network, emulator, or private fixture dependencies. — docs/contributor/xray-regression-matrix.md indexes the whole surface and splits CI-offline lanes from device/emulator, live-network, and private…
