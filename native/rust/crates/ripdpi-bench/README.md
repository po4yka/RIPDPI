# ripdpi-bench

**Layer:** L0 -- support / test / dev.

`ripdpi-bench` is the native Criterion benchmark harness for runtime and diagnostics hot paths.

## Boundaries

- Benchmark code only; do not add production APIs for other crates to consume.
- Keep benchmark fixtures sanitized and deterministic enough for local comparison.

## Checks

Run focused benchmarks from the native workspace with `cargo bench -p ripdpi-bench`.
