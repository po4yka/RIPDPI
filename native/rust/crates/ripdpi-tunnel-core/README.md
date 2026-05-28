# ripdpi-tunnel-core

**Layer:** L4 -- runtime / application.

`ripdpi-tunnel-core` owns the TUN-to-SOCKS runtime loop and composes tunnel config, TUN driver I/O, DNS, SOCKS, interception, platform primitives, and optional io_uring support.

## Dependencies

- **Upstream:** `ripdpi-tunnel-config`, `ripdpi-tunnel-intercept`, `ripdpi-tun-driver`, `ripdpi-dns-resolver`, `ripdpi-collections`, `ripdpi-runtime-platform`, `ripdpi-privileged-ops`, `ripdpi-socks5-core`, `ripdpi-io-uring`.
- **Downstream:** `ripdpi-tunnel-android`.

## Boundaries

- Tunnel runtime orchestration belongs here.
- Android JNI and service lifecycle stay in `ripdpi-tunnel-android` and Kotlin service code.

## Checks

Run focused checks with `cargo test -p ripdpi-tunnel-core`.
