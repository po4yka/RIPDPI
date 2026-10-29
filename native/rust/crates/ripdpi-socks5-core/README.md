# ripdpi-socks5-core

**Layer:** L1 -- protocol / core.

`ripdpi-socks5-core` provides SOCKS5 client protocol primitives. Its consumers are `ripdpi-diagnostics-transport`, `ripdpi-dns-resolver`, `ripdpi-proxy-runtime-adapter`, and `ripdpi-tunnel-core`. The `server` module is test-only. The crate has no SOCKS4 code; SOCKS4 inbound handling lives in `ripdpi-proxy-runtime`.

## Boundaries

- SOCKS protocol parsing and framing belong here.
- Relay selection, DNS policy, and Android service lifecycle belong in higher-level crates.

## Checks

Run focused checks with `cargo test -p ripdpi-socks5-core`.
