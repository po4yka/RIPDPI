# ripdpi-strategy-lua

**Layer:** L3 -- domain logic.

`ripdpi-strategy-lua` provides the optional Lua strategy backend.

## Dependencies

- **Upstream:** `ripdpi-strategy-trait`.
- **Downstream:** feature-gated through `ripdpi-strategy-registry`.

## Boundaries

- Lua strategy execution belongs here.
- Built-in strategy registration stays in `ripdpi-strategy-registry`; raw packet sending stays in runtime/platform crates.

## Checks

Run focused checks with `cargo test -p ripdpi-strategy-lua`.
