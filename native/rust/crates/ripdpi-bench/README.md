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
outside the timed loop). Covers all 7 transports: VLESS+Reality,
VLESS-over-xHTTP-over-Reality, ShadowTLS v3, MASQUE (H2 CONNECT-TCP), WS-tunnel
(WebTunnel), Hysteria 2, and TUIC v5. See the bench module doc for the
per-transport fixture notes.

Run just this bench:

```sh
cargo bench -p ripdpi-bench --bench protocol_throughput
```

### Baselines and the regression lane

The committed baseline is `scripts/ci/rust-bench-baseline.json` (one entry per
Criterion key, e.g. `protocol-throughput/tuic_1MiB`, with `mean_ns` / `median_ns`
and a `maxRegressionPercent`). `scripts/ci/check-criterion-regressions.py`
discovers `native/rust/target/criterion/**/new/estimates.json` and compares mean
against that baseline.

Baselines are **not committed from developer machines** — Criterion throughput is
host-dependent, so a dev-box baseline would gate CI on hardware noise. They are
captured on the CI reference runner. Wiring (in the `rust-criterion-bench` job of
`.github/workflows/ci.yml`, which runs on the nightly `schedule`):

- **Nightly (`schedule`) — enforced:** runs the bench and fails the lane on a
  `>20%` mean regression in any `protocol-throughput/*` transport
  (`--only-prefix 'protocol-throughput/' --max-regression-percent 20`, no
  `--warn-only`). The 20% gate tolerates shared-runner noise while still catching
  the 25% definition-of-done slowdown. Until the baseline holds the 7
  `protocol-throughput/*` keys this is a safe no-op (missing key → warning → pass).
- **PRs / manual dispatch — advisory:** full suite with `--warn-only` (early
  warnings, never a hard fail on heterogeneous PR hardware).

To (re)capture the reference baseline:

1. Run the **CI** workflow manually: Actions → CI → *Run workflow* with
   `capture_criterion_baseline = true`. It runs the full bench on the reference
   runner and uploads a `rust-bench-baseline-candidate` artifact (full
   `--dump-current` result set).
2. Download the artifact, review the numbers, and commit it to
   `scripts/ci/rust-bench-baseline.json` via a normal reviewed PR. Baselines are
   never auto-committed (see `.claude/rules/golden-bless-discipline.md` by
   analogy). Merging that PR arms the nightly enforced lane.
