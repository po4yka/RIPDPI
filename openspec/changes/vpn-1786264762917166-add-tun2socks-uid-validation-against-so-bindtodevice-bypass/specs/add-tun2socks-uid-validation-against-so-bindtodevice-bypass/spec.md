## Purpose

Define the observable completion contract for Add tun2socks UID validation to close SO_BINDTODEVICE escape (kernel 5.7+). On Linux kernel 5.7+ (predominantly Android 12+, API 31+), SOBINDTODEVICE privilege was dropped — any unprivileged app can bind a socket directly to a network interface (e.g., tun0) and bypass all Android VPN split-tunneling routing rules. Standard tun2socks reads packets off the TUN interface but has no UID attribution, so any per-app split-tunnel enforcement done at the routing layer is invisible to it

## ADDED Requirements

### Requirement: REQ-VPN-1786264762917166-001 — PR description confirms current state of ripdpi-tun-driver UID validation (pres…

The RIPDPI implementation MUST satisfy this portfolio criterion: PR description confirms current state of ripdpi-tun-driver UID validation (present or absent). Absent — ripdpi-tun-driver is TUN open/configure only; ripdpi-flow-app-attribution calls getConnectionOwnerUid for attribution/learning, not as an enforcement gate.….

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that PR description confirms current state of ripdpi-tun-driver UID validation (present or absent). Absent — ripdpi-tun-driver is TUN open/configure only; ripdpi-flow-app-attribution calls getConnectionOwnerUid for attribution/learning, not as an enforcement gate.…

### Requirement: REQ-VPN-1786264762917166-002 — If absent: per-packet UID validation implemented in the tun2socks layer. UidFlo…

The RIPDPI implementation MUST satisfy this portfolio criterion: If absent: per-packet UID validation implemented in the tun2socks layer. UidFlowPolicy is consulted at the live smoltcp TCP and UDP admission seams, the JNI getConnectionOwnerUid attribution source is registered with the native session, and readiness remains….

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that If absent: per-packet UID validation implemented in the tun2socks layer. UidFlowPolicy is consulted at the live smoltcp TCP and UDP admission seams, the JNI getConnectionOwnerUid attribution source is registered with the native session, and readiness remains…

### Requirement: REQ-VPN-1786264762917166-003 — Integration test: synthetic app uses SOBINDTODEVICE=tun0; without countermeasur…

The RIPDPI implementation MUST satisfy this portfolio criterion: Integration test: synthetic app uses SOBINDTODEVICE=tun0; without countermeasure, connection succeeds; with countermeasure, traffic is denied. The platform-neutral unprivileged Linux process oracle and the separate-UID Android test process now prove the real-….

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Integration test: synthetic app uses SOBINDTODEVICE=tun0; without countermeasure, connection succeeds; with countermeasure, traffic is denied. The platform-neutral unprivileged Linux process oracle and the separate-UID Android test process now prove the real-…

### Requirement: REQ-VPN-1786264762917166-004 — Verify via adb shell cat /proc/net/tcp that no leaked connection appears to the…

The RIPDPI implementation MUST satisfy this portfolio criterion: Verify via adb shell cat /proc/net/tcp that no leaked connection appears to the remote host post-countermeasure. DEVICE-GATED.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Verify via adb shell cat /proc/net/tcp that no leaked connection appears to the remote host post-countermeasure. DEVICE-GATED

### Requirement: REQ-VPN-1786264762917166-005 — Protocol-specific denial behavior is evidenced

The implementation MUST preserve the declared TCP reset, UDP drop and attribution cache, configurable ICMP policy, and MapDNS admission boundaries with observable evidence for every device-gated remainder.

#### Scenario: Verify protocol-specific denial

- **WHEN** unauthorized TCP, UDP, ICMP, and MapDNS traffic exercises the live tunnel path
- **THEN** each flow MUST follow its declared denial policy without retaining an unauthorized route

### Requirement: REQ-VPN-1786264762917166-006 — Kernel-version gating is verified

The UID validation policy MUST be exercised on both a kernel 5.7+ Android device and a pre-5.7 Android device.

#### Scenario: Verify kernel-version boundary

- **WHEN** the same acceptance journey runs on devices on either side of kernel 5.7
- **THEN** the observed behavior MUST match the documented version gate and fail-closed fallback
