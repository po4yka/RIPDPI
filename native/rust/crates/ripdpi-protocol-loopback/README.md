# ripdpi-protocol-loopback

**Layer:** L1 -- protocol / core.

`ripdpi-protocol-loopback` is a development/test harness for in-process protocol loopback servers.

## Boundaries

- Test harness and loopback fixture code belongs here.
- Protocol client implementations and runtime relay integration stay in the protocol or relay crates.

## Checks

Run focused checks with `cargo test -p ripdpi-protocol-loopback`.
