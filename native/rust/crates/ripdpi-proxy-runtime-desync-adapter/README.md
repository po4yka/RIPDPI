# ripdpi-proxy-runtime-desync-adapter

**Layer:** L4 -- runtime / application.

`ripdpi-proxy-runtime-desync-adapter` adapts desync planning and execution types into the proxy runtime boundary.

## Dependencies

- **Upstream:** `ripdpi-config`, `ripdpi-desync`, `ripdpi-desync-runtime`, `ripdpi-failure-classifier`, `ripdpi-ipfrag`, `ripdpi-packets`, `ripdpi-proxy-config`, `ripdpi-runtime-api`, `ripdpi-runtime-decision-ports`, `ripdpi-runtime-platform`, `ripdpi-runtime-services`, `ripdpi-session`.
- **Downstream:** `ripdpi-proxy-runtime` and related runtime wiring.

## Boundaries

- Keep this crate as adapter glue between runtime execution and desync domain crates.
- New desync algorithms belong in `ripdpi-desync`; platform operations belong in `ripdpi-runtime-platform`.

## Checks

Run focused checks with `cargo test -p ripdpi-proxy-runtime-desync-adapter`.
