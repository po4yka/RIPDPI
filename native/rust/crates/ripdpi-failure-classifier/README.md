# ripdpi-failure-classifier

**Layer:** L3 -- domain logic.

`ripdpi-failure-classifier` classifies observed protocol fields and responses into blocking, transport, and policy-relevant failure signals.

## Dependencies

- **Upstream:** `ripdpi-packets`.
- **Downstream:** diagnostics, runtime policy, runtime adaptive logic, and proxy/runtime adapters.

## Boundaries

- Keep classification pure over extracted evidence where possible.
- Packet parsing and field extraction stay in `ripdpi-packets`; active probing stays in diagnostics crates.

## Checks

Run focused checks with `cargo test -p ripdpi-failure-classifier`.
