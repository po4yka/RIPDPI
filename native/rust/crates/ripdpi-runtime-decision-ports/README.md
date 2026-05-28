# ripdpi-runtime-decision-ports

**Layer:** L2 -- contracts / config.

`ripdpi-runtime-decision-ports` defines narrow selected-decision traits and DTOs for socket runtime execution.

## Dependencies

- **Upstream:** `ripdpi-config`, `ripdpi-desync`, `ripdpi-failure-classifier`, `ripdpi-proxy-config`.
- **Downstream:** decision engine, runtime services, proxy-runtime adapters, and runtime execution code.

## Boundaries

- Stable contracts and snapshots belong here.
- Concrete policy/adaptive implementations and socket I/O belong in downstream domain/runtime crates.

## Checks

Run focused checks with `cargo test -p ripdpi-runtime-decision-ports`.
