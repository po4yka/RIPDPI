# ripdpi-runtime-decision-engine

**Layer:** L3 -- domain logic.

`ripdpi-runtime-decision-engine` is the decision boundary that composes policy, adaptive, and direct-path inputs into runtime decision outputs.

## Dependencies

- **Upstream:** `ripdpi-config`, `ripdpi-proxy-config`, `ripdpi-runtime-decision-ports`, `ripdpi-runtime-services`.
- **Downstream:** runtime adapters and tests that need the composed decision engine.

## Boundaries

- Decision composition belongs here.
- Socket execution remains in `ripdpi-proxy-runtime`; individual port traits and DTOs remain in `ripdpi-runtime-decision-ports`.

## Checks

Run focused checks with `cargo test -p ripdpi-runtime-decision-engine`.
