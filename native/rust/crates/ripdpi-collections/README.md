# ripdpi-collections

**Layer:** L1 -- protocol / core.

`ripdpi-collections` contains small generic data structures shared by native crates.

## Boundaries

- Keep it domain-neutral: no Android, JNI, relay, diagnostics, or strategy policy.
- Add a type here only when multiple crates need the same collection behavior and a standard-library type is not enough.

## Checks

Run focused checks with `cargo test -p ripdpi-collections`.
