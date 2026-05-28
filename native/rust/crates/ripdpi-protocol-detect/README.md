# ripdpi-protocol-detect

**Layer:** L1 -- protocol / core.

`ripdpi-protocol-detect` contains stream protocol-detection helpers built around the shared strategy trait surface.

## Dependencies

- **Upstream:** `ripdpi-strategy-trait`.
- **Downstream:** currently standalone/test-oriented; keep consumers explicit before broadening the API.

## Boundaries

- Detection primitives belong here.
- Runtime policy, diagnostics orchestration, and packet mutation belong in their dedicated crates.

## Checks

Run focused checks with `cargo test -p ripdpi-protocol-detect`.
