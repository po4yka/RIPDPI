# ripdpi-bench

**Layer:** L0 -- support / test / dev.

`ripdpi-bench` is the native Criterion benchmark harness for runtime and diagnostics hot paths.

## Boundaries

- Benchmark code only; do not add production APIs for other crates to consume.
- Keep benchmark fixtures sanitized and deterministic enough for local comparison.

## Checks

Run focused benchmarks from the native workspace with `cargo bench -p ripdpi-bench`.

## Per-transport throughput (`protocol_throughput`)

`benches/protocol_throughput.rs` measures steady-state 1 MiB full-duplex
throughput of each transport's data path by driving the real protocol client
against its in-process loopback server fixture (handshake established once,
outside the timed loop). Covered today: VLESS+Reality,
VLESS-over-xHTTP-over-Reality, and ShadowTLS v3. See the bench module doc for the
deferred transports and why.

Run just this bench:

```sh
cargo bench -p ripdpi-bench --bench protocol_throughput
```

### Baselines

Baselines are **not committed from developer machines** — Criterion throughput is
host-dependent, and a dev-box baseline would gate CI on hardware noise. The
`regression-detector` baseline must be captured on the CI reference runner:

1. On the reference runner, run the bench to completion (full sample size, no
   `--measurement-time` override).
2. Save Criterion's `target/criterion/**/new/estimates.json` for each case as the
   committed baseline under `native/rust/crates/ripdpi-bench/baselines/`.
3. Wire `regression-detector` to compare against those committed numbers in the
   nightly lane (flip `check-criterion-regressions.py` off `--warn-only` only once
   the reference baseline exists).
