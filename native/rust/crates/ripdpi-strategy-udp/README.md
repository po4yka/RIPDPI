# ripdpi-strategy-udp

**Layer:** L3 -- domain logic.

`ripdpi-strategy-udp` contains UDP packet-level desync strategies registered through the shared strategy trait surface.

## Dependencies

- **Upstream:** `ripdpi-strategy-trait`.
- **Downstream:** `ripdpi-strategy-registry` and TUN-egress strategy execution.

## Boundaries

- UDP strategy implementation belongs here.
- UDP relay transport and socket execution belong in relay/runtime crates.

## Checks

Run focused checks with `cargo test -p ripdpi-strategy-udp`.
