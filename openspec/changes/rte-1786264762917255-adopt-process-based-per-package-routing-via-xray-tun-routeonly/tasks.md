# RTE-1786264762917255: Adopt process-based per-package routing via Xray TUN routeOnly

## Objective

Adopt process-based per-package routing via Xray TUN routeOnly

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] RTE-1786264762918248 Per-package routing enforces exclusions via VpnAppExclusionPolicy using VpnService.Builder addAllowedApplication/addDisallowedApplication (implemented; note: routeOnly Xray TUN pattern from the task title was not adopted — RIPDPI uses the… #feature @item:RTE-1786264762917255
- [x] RTE-1786264762918389 UI exposes per-package allowlist (route through tunnel) and blocklist (route direct) #feature @item:RTE-1786264762917255
- [x] RTE-1786264762918168 Default blocklist seeds with known platform-detection-positive apps per platform-vpn-detection-april-2026 #feature @item:RTE-1786264762917255
- [ ] RTE-1786266573979890 Verify on device that blocklisted apps use direct egress while allowed apps use the configured tunneled egress #feature @item:RTE-1786264762917255

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
