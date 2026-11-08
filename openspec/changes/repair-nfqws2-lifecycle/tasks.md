# VPN-1794158885036621

## Objective

Repair F1 through F5 and observe the named regression checks.

## Ownership

Kotlin and Rust writers own the paths in design.md and the portfolio task. The integration writer alone owns planning, combined gates, and main integration.

## Execution

- [ ] VPN-1794159017607315 Repair F1 monitor registration and run dead-helper session regressions #bug !high @item:VPN-1794158885036621
- [ ] VPN-1794159020495755 Repair F3 scoped predecessor shutdown and run ownership regressions #bug !high @item:VPN-1794158885036621
- [ ] VPN-1794159023606659 Repair F4 firewall chain ownership and run legacy UID collision regressions #bug !high @item:VPN-1794158885036621
- [ ] VPN-1794159025344157 Repair F5 compressed Lua validation and run file boundary regressions #bug !high @item:VPN-1794158885036621
- [ ] VPN-1794159031763874 Repair F2 stale cleanup debt and run timeout recovery regressions #bug !high @item:VPN-1794158885036621
- [ ] VPN-1794159033246872 Review combined repairs run affected gates and integrate separate finding commits #bug !high @item:VPN-1794158885036621

## Verification

Run affected Kotlin tests, root-helper and protocol Cargo tests/clippy with --locked, service static analysis, just ci-gates, architecture-health, locked cargo metadata, and independent review. Record device and hosted CI gaps without substituting host evidence.
