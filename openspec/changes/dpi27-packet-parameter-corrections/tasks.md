# RST-1794677915101106: Correct DPI-27 packet parameter contracts

## Objective

Correct seven findings with isolated ownership and observed local evidence.

## Ownership

Follow the exact worker lanes in docs/tasks/issues/dpi27-packet-parameter-corrections.md. The integrator owns task records and generated API reports. No shared lockfile or baseline changes.

## Execution

- [x] RST-1794678235803087 F1 associate adaptive feedback with its attempt and verify stale results #bug !high @item:RST-1794677915101106
- [x] RST-1794678245393545 F2 reserve non-atomic raw IPv4 IDs and verify cross-flow bytes #bug !high @item:RST-1794677915101106
- [x] RST-1794678253764740 F3 reject false SACK matches with kernel regressions #bug !high @item:RST-1794677915101106
- [x] RST-1794678258153648 F4 preserve fixed UDP TTL and verify mixed configuration #bug !high @item:RST-1794677915101106
- [x] RST-1794678262798271 F5 enumerate boundary TTL candidates once per cycle #bug !high @item:RST-1794677915101106
- [x] RST-1794678269799237 F6 align timestamp schema and tests with receive filtering #bug @item:RST-1794677915101106

## Verification

Targeted red/green regressions, affected Rust suites, strict Clippy, unprivileged Linux byte/kernel tests, locale lint, generated contracts, ci-gates and independent review.
- [x] RST-1794683533527463 F7 correct DISOOB fake TTL scope in settings and all locales #bug @item:RST-1794677915101106
