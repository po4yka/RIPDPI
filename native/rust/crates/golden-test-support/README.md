# golden-test-support

**Layer:** L0 -- support / test / dev.

`golden-test-support` provides shared helpers for deterministic golden-file tests in the native Rust workspace, including fixture path resolution and blessing-aware write helpers.

## Boundaries

- Test-support crate only; production crates should not depend on it from normal dependencies.
- Keep environment-variable and fixture-path conventions centralized here instead of duplicating them across crate tests.

## Checks

Run focused checks with `cargo test -p golden-test-support`.
