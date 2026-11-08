# VPN-1794140727483999

## Objective

Repair F1 through F5 and verify the combined native TCP bridge.

## Ownership

See the portfolio task for exact write lanes and serialized Cargo.lock ownership.

## Execution

- [x] VPN-1794140849743430 Repair F1 half-close delivery and retain bounded TCP close state with regression tests #bug !high @item:VPN-1794140727483999
- [x] VPN-1794140852477934 Repair F2 relay failure cleanup after upstream EOF with idle regression tests #bug !high @item:VPN-1794140727483999
- [x] VPN-1794140854875280 Repair F3 tunnel interface entropy with repeated-instance regression tests #bug !high @item:VPN-1794140727483999
- [x] VPN-1794140858454513 Repair F4 configured native TCP buffers with window and capacity regressions #bug @item:VPN-1794140727483999
- [x] VPN-1794140863320088 Repair F5 IPv6 default MSS in pinned upstream source with family regressions #bug @item:VPN-1794140727483999
- [x] VPN-1794140869499117 Review repairs and run combined Rust and source gates before main integration #chore !high @item:VPN-1794140727483999

## Verification

Run pinned-toolchain Rust regressions with --locked, affected clippy and formatting, independent review, just ci-gates, architecture health and locked Cargo metadata.
