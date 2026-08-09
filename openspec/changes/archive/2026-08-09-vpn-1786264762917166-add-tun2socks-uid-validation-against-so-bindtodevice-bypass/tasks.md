# VPN-1786264762917166: Add tun2socks UID validation to close SO_BINDTODEVICE escape (kernel 5.7+)

## Objective

Add tun2socks UID validation to close SO_BINDTODEVICE escape (kernel 5.7+)

## Ownership

- TUN TCP, UDP, ICMP, and MapDNS admission paths
- Kotlin/JNI UID attribution bridge and physical evidence harness

## Execution

- [x] VPN-1786264762917376 Confirm packet forwarding originally lacked UID enforcement #feature !high @item:VPN-1786264762917166
- [x] VPN-1786264762917494 Enforce fail-closed UID policy at live TCP and UDP admission seams #feature !high @item:VPN-1786264762917166
- [x] VPN-1786264762917458 Prove unprivileged bound-device traffic is admitted for allowed UIDs and denied for excluded UIDs #feature !high @item:VPN-1786264762917166
- [x] VPN-1786266573979046 Prove TCP reset, UDP drop, ICMP policy, and MapDNS exact-tuple denial boundaries #feature !high @item:VPN-1786264762917166
- [x] VPN-1786266573979750 Gate enforcement by the observable attribution/bind capability and fail closed when unavailable #feature !high @item:VPN-1786264762917166
- [x] VPN-1786264762917653 Prove zero denied delivery, packet-path counters, exact denial outcomes, and post-denial liveness #feature !high @item:VPN-1786264762917166

## Verification

- focused TUN policy, JNI attribution, ICMP, MapDNS, and physical harness tests
- Linux privileged run `29652023020` plus Android evidence recorded in the portfolio work log
