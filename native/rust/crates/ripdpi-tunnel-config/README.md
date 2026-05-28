# ripdpi-tunnel-config

**Layer:** L2 -- contracts / config.

`ripdpi-tunnel-config` defines the native configuration model for the TUN-to-SOCKS tunnel runtime.

## Boundaries

- Tunnel config structs and serialization contracts belong here.
- Runtime I/O belongs in `ripdpi-tunnel-core`; JNI conversion belongs in `ripdpi-tunnel-android`.

## Checks

Run focused checks with `cargo test -p ripdpi-tunnel-config`.
