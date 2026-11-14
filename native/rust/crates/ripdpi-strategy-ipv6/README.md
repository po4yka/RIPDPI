# ripdpi-strategy-ipv6

**Layer:** L3 -- domain logic.

`ripdpi-strategy-ipv6` contains IPv6 extension-header desync strategies registered through the shared strategy trait surface.

## Dependencies

- **Upstream:** `ripdpi-strategy-trait`.
- **Downstream:** `ripdpi-strategy-registry` and TUN-egress strategy execution.

## Boundaries

- IPv6 strategy implementation belongs here.
- Packet emission and platform capability checks belong in runtime/platform crates.
- Injection supports one Hop-by-Hop or Destination Options header before bare
  IPv6/TCP. It does not support a general extension-header chain or SRv6.
- `routing` is rejected. The former eight-byte Routing Type 4 had no segment list.
- TUN injection sends a copy and retains the original packet. The raw sender
  requires Linux/Android socket privileges; VPN mode alone does not grant them.

## Checks

Run focused checks with `cargo test -p ripdpi-strategy-ipv6`.
