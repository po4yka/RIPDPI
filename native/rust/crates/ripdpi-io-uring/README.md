# ripdpi-io-uring

**Layer:** L5 -- platform / privileged.

`ripdpi-io-uring` provides optional Linux/Android io_uring support and capability probing for zero-copy-oriented networking paths.

## Boundaries

- Linux/Android primitive only; higher-level tunnel or proxy policy belongs outside this crate.
- Callers should route through the platform/runtime facades rather than coupling app logic directly to io_uring details.

## Checks

Run focused checks with `cargo test -p ripdpi-io-uring`.
