# VPN-1786264762917166: Add tun2socks UID validation to close SO_BINDTODEVICE escape (kernel 5.7+)

## Objective

Add tun2socks UID validation to close SO_BINDTODEVICE escape (kernel 5.7+)

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] VPN-1786264762917376 PR description confirms current state of ripdpi-tun-driver UID validation (present or absent). Absent — ripdpi-tun-driver is TUN open/configure only; ripdpi-flow-app-attribution calls getConnectionOwnerUid for attribution/learning, not as… #feature !high @item:VPN-1786264762917166
- [x] VPN-1786264762917494 If absent: per-packet UID validation implemented in the tun2socks layer. UidFlowPolicy is consulted at the live smoltcp TCP and UDP admission seams, the JNI getConnectionOwnerUid attribution source is registered with the native session, an… #feature !high @item:VPN-1786264762917166
- [x] VPN-1786264762917458 Integration test: synthetic app uses SOBINDTODEVICE=tun0; without countermeasure, connection succeeds; with countermeasure, traffic is denied. The platform-neutral unprivileged Linux process oracle and the separate-UID Android test process… #feature !high @item:VPN-1786264762917166
- [ ] VPN-1786266573979046 Complete protocol-specific denial evidence for TCP, UDP, ICMP, and MapDNS boundaries; device-only evidence remains open #feature !high @item:VPN-1786264762917166
- [ ] VPN-1786266573979750 Verify version gating on both kernel 5.7+ and a pre-5.7 Android device #feature !high @item:VPN-1786264762917166
- [ ] VPN-1786264762917653 Verify via adb shell cat /proc/net/tcp that no leaked connection appears to the remote host post-countermeasure. DEVICE-GATED #feature !high @item:VPN-1786264762917166

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
