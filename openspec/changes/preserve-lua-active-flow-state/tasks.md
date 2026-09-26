# RST-1790431269626912: Preserve Lua state across active tunnel flows

## Objective

Preserve Lua state under high flow concurrency and release it at observed flow end.

## Ownership

One writer owns `ripdpi-strategy-lua`, `ripdpi-strategy-trait`, `ripdpi-strategy-registry`, `ripdpi-tunnel-intercept`, `ripdpi-tunnel-core`, tests, and the trait API snapshot. Review agents are read-only.

## Execution

## Verification

Run affected Cargo tests with `--locked`, format check, API snapshot check, architecture health, `cargo metadata --locked`, and task validation. Report hosted CI and device checks separately.
- [x] RST-1790431473866720 Reject Lua state overflow without evicting admitted flows #bug !high @item:RST-1790431269626912
- [x] RST-1790431477830383 Connect TCP and UDP tunnel end events to Lua state cleanup #bug !high @item:RST-1790431269626912
- [x] RST-1790431482078217 Add TUN high-concurrency lifecycle regressions and run Rust gates #bug !high @item:RST-1790431269626912
