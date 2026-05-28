# ripdpi-tun-driver

**Layer:** L5 -- platform / privileged.

`ripdpi-tun-driver` owns raw TUN device driver primitives used by the tunnel runtime.

## Boundaries

- TUN device I/O primitives belong here.
- TUN-to-SOCKS runtime orchestration belongs in `ripdpi-tunnel-core`; Android JNI entrypoints belong in `ripdpi-tunnel-android`.

## Checks

Run focused checks with `cargo test -p ripdpi-tun-driver`.
