# ripdpi-desync-runtime

**Layer:** L3 -- domain logic.

`ripdpi-desync-runtime` lowers desync configuration and planning data into runtime-ready execution structures while keeping socket I/O outside the crate.

## Dependencies

- **Upstream:** `ripdpi-config`, `ripdpi-desync`, `ripdpi-ipfrag`, `ripdpi-packets`, `ripdpi-proxy-config`, `ripdpi-session`.
- **Downstream:** proxy-runtime desync adapter and runtime execution crates.

## Boundaries

- Runtime preparation of desync plans belongs here.
- Actual socket operations and platform capability dispatch belong in `ripdpi-runtime-platform` or runtime adapters.

## Checks

Run focused checks with `cargo test -p ripdpi-desync-runtime`.
