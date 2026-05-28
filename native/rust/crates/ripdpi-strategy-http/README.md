# ripdpi-strategy-http

**Layer:** L3 -- domain logic.

`ripdpi-strategy-http` contains HTTP-layer desync strategy helpers registered through the shared strategy trait surface.

## Dependencies

- **Upstream:** `ripdpi-strategy-trait`.
- **Downstream:** `ripdpi-strategy-registry`.

## Boundaries

- HTTP-specific strategy implementation belongs here.
- Registry aggregation belongs in `ripdpi-strategy-registry`; config schema belongs in `ripdpi-strategy-config`.

## Checks

Run focused checks with `cargo test -p ripdpi-strategy-http`.
