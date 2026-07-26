# ripdpi-runtime-dns-cache

**Layer:** L4 -- runtime / application.

`ripdpi-runtime-dns-cache` contains runtime DNS cache primitives for route-aware native DNS policy work. `ripdpi-ws-bootstrap` consumes it when coordinating WebSocket tunnel bootstrap.

## Boundaries

- Runtime cache data structures and cache policy belong here.
- Resolver protocols belong in `ripdpi-dns-resolver`; Android persistence and UI state belong outside this crate.

## Checks

Run focused checks with `cargo test -p ripdpi-runtime-dns-cache`.
