# native-soak-support

**Layer:** L0 -- support / test / dev.

`native-soak-support` contains helpers for native soak tests, including process-level coordination primitives used by long-running local or CI soak lanes.

## Boundaries

- Test/soak support only; do not put runtime policy or production networking logic here.
- Keep helpers deterministic and safe to run from repeated CI jobs.

## Checks

Run focused checks with `cargo test -p native-soak-support`.
