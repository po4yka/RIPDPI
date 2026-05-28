# ripdpi-socks5-core

**Layer:** L1 -- protocol / core.

`ripdpi-socks5-core` provides SOCKS5/SOCKS4 protocol primitives used by relay, tunnel, DNS, and proxy paths.

## Boundaries

- SOCKS protocol parsing and framing belong here.
- Relay selection, DNS policy, and Android service lifecycle belong in higher-level crates.

## Checks

Run focused checks with `cargo test -p ripdpi-socks5-core`.
