# VPN-1794196711383747

## Objective

Repair the current IPC audit defects with separate tested commits.

## Ownership

Use the isolated ownership lanes in design.md and the portfolio task. Integration owns shared task/specification and contract documentation.

## Execution

- [x] VPN-1794196899260030 Preserve close-on-exec and descriptor ownership through replacement; run owning Linux regressions #bug !high @item:VPN-1794196711383747
- [x] VPN-1794196899730212 Reject impossible ordered payload allocation and verify handler liveness and fd cleanup #bug !high @item:VPN-1794196711383747
- [x] VPN-1794196900171780 Reproduce and repair total deadlines and invalid descriptor types in protocol owners #bug !high @item:VPN-1794196711383747
- [x] VPN-1794196900616146 Verify existing Kotlin repairs, non-root compatibility, combined gates and independent review #bug !high @item:VPN-1794196711383747

## Verification

Run owning native Linux regressions and clippy with --locked, Kotlin helper tests, ci-gates, architecture health --check, locked cargo metadata, ARM64 helper compilation, and independent read-only review. Root activation and device acceptance remain separate.
