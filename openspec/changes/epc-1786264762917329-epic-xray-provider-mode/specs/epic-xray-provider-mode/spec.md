## Purpose

Define the observable completion contract for Epic - Xray provider mode. Add a first remote VPN-client provider mode to RIPDPI by embedding xray-core through libXray, with VLESS/REALITY and XHTTP as the initial profile targets

## ADDED Requirements

### Requirement: REQ-EPC-1786264762917329-001 — RIPDPI can start Android VPN mode with Xray selected as the active provider. —…

The RIPDPI implementation MUST satisfy this portfolio criterion: RIPDPI can start Android VPN mode with Xray selected as the active provider. — OPEN: requires the real libXray bridge (RunXrayFromJSON) which needs the gomobile-built AAR + NDK29 native link + a device; none are present in the build environment, so a real Xra….

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that RIPDPI can start Android VPN mode with Xray selected as the active provider. — OPEN: requires the real libXray bridge (RunXrayFromJSON) which needs the gomobile-built AAR + NDK29 native link + a device; none are present in the build environment, so a real Xra…

### Requirement: REQ-EPC-1786264762917329-002 — At least VLESS/REALITY and XHTTP profile shapes validate and render to Xray JSO…

The RIPDPI implementation MUST satisfy this portfolio criterion: At least VLESS/REALITY and XHTTP profile shapes validate and render to Xray JSON without leaking secrets. — XrayConfigRenderer + XrayConfigValidator + XrayProfileRedactor, golden- and redaction-tested green offline.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that At least VLESS/REALITY and XHTTP profile shapes validate and render to Xray JSON without leaking secrets. — XrayConfigRenderer + XrayConfigValidator + XrayProfileRedactor, golden- and redaction-tested green offline

### Requirement: REQ-EPC-1786264762917329-003 — Xray sockets are protected from the VPN loop, including DNS and listener paths.…

The RIPDPI implementation MUST satisfy this portfolio criterion: Xray sockets are protected from the VPN loop, including DNS and listener paths. — the protect-first ordering, DNS-loop avoidance, and protect-fd contract are test-proven offline against the runtime/bridge contract (XrayProtectFdContractTest, XrayDnsLoopRegres….

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Xray sockets are protected from the VPN loop, including DNS and listener paths. — the protect-first ordering, DNS-loop avoidance, and protect-fd contract are test-proven offline against the runtime/bridge contract (XrayProtectFdContractTest, XrayDnsLoopRegres…

### Requirement: REQ-EPC-1786264762917329-004 — Home, Diagnostics, and Settings show typed Xray provider state. — the typed pro…

The RIPDPI implementation MUST satisfy this portfolio criterion: Home, Diagnostics, and Settings show typed Xray provider state. — the typed provider-state substrate (XrayProviderSnapshot, XrayConnectionStage, failure classes, redacted summaries) AND the :core:service live-population backend now both landed and are CI-test….

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Home, Diagnostics, and Settings show typed Xray provider state. — the typed provider-state substrate (XrayProviderSnapshot, XrayConnectionStage, failure classes, redacted summaries) AND the :core:service live-population backend now both landed and are CI-test…

### Requirement: REQ-EPC-1786264762917329-005 — Lifecycle, config, protect-fd, telemetry, and smoke tests cover the first inter…

The RIPDPI implementation MUST satisfy this portfolio criterion: Lifecycle, config, protect-fd, telemetry, and smoke tests cover the first internal build. — lifecycle, config, protect-fd, DNS-loop, and telemetry tests are green offline; the device/emulator egress smoke remains OPEN (blocked on gomobile/libXray + NDK29 + de….

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Lifecycle, config, protect-fd, telemetry, and smoke tests cover the first internal build. — lifecycle, config, protect-fd, DNS-loop, and telemetry tests are green offline; the device/emulator egress smoke remains OPEN (blocked on gomobile/libXray + NDK29 + de…
