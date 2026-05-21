# ripdpi-strategy-window

**Responsibility:** the TCP window-clamp desync strategies — `wsize` (direct
clamp value) and `wssize` (size `<<` scale). A representative, minimal
`ripdpi-strategy-*` implementation crate.
**Layer:** L3 — domain logic.

## How a strategy implementation crate is wired

This crate is the smallest worked example of the strategy seam — a new
implementation crate follows the same three steps:

1. **Implement `DesyncStrategy`** for each strategy type (`WsizeStrategy`,
   `WssizeStrategy`) — `id` / `matches` / `plan` / `describe`. `plan` is
   capability-gated (`RuntimeCapability::TcpWindowClamp`); a strategy that
   lacks its capability returns an empty plan rather than failing.
2. **Contribute a `StrategyFactory`** to the `STRATEGY_FACTORIES` `linkme`
   slice for each stable ID — `#[linkme::distributed_slice(...)]` over a
   zero-argument `make` function.
3. `ripdpi-strategy-registry` **force-links** this crate
   (`extern crate ripdpi_strategy_window as _;`) so the slice entries reach the
   final binary, then resolves `wsize` / `wssize` by ID with **no central match
   arm**.

The only central edit a factory-backed strategy needs is the `extern crate`
line in `ripdpi-strategy-registry`. See
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§1.

## Dependency direction

**Upstream:** `ripdpi-strategy-trait`, `linkme`. **Downstream:** force-linked by
`ripdpi-strategy-registry`.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md) and
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md) §1.
