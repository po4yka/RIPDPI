# VPN-1794141873537976

## Objective

Repair the five UDP contract faults and verify the remaining hypotheses.

## Ownership

Association worker owns udp_assoc and the destination GC call in phases.rs. Datagram worker owns receive storage and return fragmentation. Provider worker owns the managed Xray patch, renderer tag and tests. Coordinator owns ingress validation, planning and combined gates. Worker branches remain isolated.

## Verification

Use targeted RED/GREEN tests, affected crate suites and lint, task validation, ci-gates, architecture health and locked metadata. Cross-module provider changes also require Kotlin tests and pinned Xray Go tests. Device and external runs are not claimed from host results.

## Execution

- [ ] VPN-1794142081038544 Repair managed Xray UDP authority handoff #bug !high @item:VPN-1794141873537976
- [ ] VPN-1794142081837995 Retain admitted UDP reply mappings at capacity #bug !high @item:VPN-1794141873537976
- [ ] VPN-1794142082448229 Expire historical UDP destination leases safely #bug !high @item:VPN-1794141873537976
- [ ] VPN-1794142083191753 Deliver complete large UDP replies within the TUN MTU #bug !high @item:VPN-1794141873537976
- [ ] VPN-1794142083879816 Reject invalid UDP ingress checksums #bug @item:VPN-1794141873537976
- [ ] VPN-1794142084413873 Verify hypotheses and combined repair gates #bug !high @item:VPN-1794141873537976
