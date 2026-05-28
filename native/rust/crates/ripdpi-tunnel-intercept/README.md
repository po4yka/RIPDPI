# ripdpi-tunnel-intercept

**Layer:** L4 -- runtime / application.

`ripdpi-tunnel-intercept` applies TUN-egress interception and strategy execution over packets flowing through the tunnel runtime.

## Dependencies

- **Upstream:** `ripdpi-packets`, `ripdpi-runtime-platform`, `ripdpi-strategy-config`, `ripdpi-strategy-ipv6`, `ripdpi-strategy-registry`, `ripdpi-strategy-trait`, `ripdpi-strategy-udp`.
- **Downstream:** `ripdpi-tunnel-core`.

## Boundaries

- Packet interception and strategy dispatch belong here.
- TUN device I/O belongs in `ripdpi-tun-driver` / `ripdpi-tunnel-core`; individual strategy implementations stay in `ripdpi-strategy-*` crates.

## Checks

Run focused checks with `cargo test -p ripdpi-tunnel-intercept`.
