# ripdpi-strategy-ipv6

**Layer:** L3 -- domain logic.

`ripdpi-strategy-ipv6` contains IPv6 extension-header desync strategies registered through the shared strategy trait surface.

## Dependencies

- **Upstream:** `ripdpi-strategy-trait`.
- **Downstream:** `ripdpi-strategy-registry` and TUN-egress strategy execution.

## Boundaries

- IPv6 strategy implementation belongs here.
- Packet emission and platform capability checks belong in runtime/platform crates.

## Checks

Run focused checks with `cargo test -p ripdpi-strategy-ipv6`.
