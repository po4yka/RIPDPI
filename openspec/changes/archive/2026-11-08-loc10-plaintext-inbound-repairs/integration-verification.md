# LOC-10 integration verification

Checked source commit: `f77d947a1b1145538d27165e28be401828a0fa17`.

This check follows the final rebase onto `82237680cfa2cbe393c89e8ac6ee45bedd3cb93d`, which includes the SOCKS5 client contract repairs. It supplements the earlier archive evidence without changing terminal receipts or the earlier verified snapshot.

The separate code commits are:

- `6c0c56e6e`: enforce plaintext first-payload routing policy.
- `0f115e5b4`: reject final listener connect candidates.
- `415723060`: expose explicit plaintext address input.

## Observed gates

All commands ran under `build-gate --lane ripdpi`, `CARGO_TARGET_DIR=/tmp/ripdpi-loc10-repairs-target`, and `CARGO_BUILD_JOBS=4`.

| Gate | Exact command | Result | Local log |
|---|---|---|---|
| packages | `cargo test --manifest-path native/rust/Cargo.toml --locked -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-packets -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-cli --lib --tests -- --test-threads=1` | exit 0, 90.71 s | `/tmp/loc10-integration-packages.log` |
| local-interop | `cargo test --manifest-path native/rust/Cargo.toml --locked -p ripdpi-proxy-runtime --test network_e2e -- --ignored --test-threads=1` | exit 0, 10.28 s | `/tmp/loc10-integration-local-interop.log` |
| clippy | `cargo clippy --manifest-path native/rust/Cargo.toml --locked -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-packets -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-cli --all-targets -- -D warnings` | exit 0, 9.28 s | `/tmp/loc10-integration-clippy.log` |
| format | `cargo fmt --manifest-path native/rust/Cargo.toml --all -- --check` | exit 0, 4.98 s | `/tmp/loc10-integration-format.log` |
| ci-gates | `just ci-gates` | exit 0, 52.92 s | `/tmp/loc10-integration-ci-gates.log` |
| metadata | `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1 --no-deps` | exit 0, 0.07 s | `/tmp/loc10-integration-metadata.log` |
| tasks | `./taskctl validate` | exit 0, 0.98 s | `/tmp/loc10-integration-tasks.log` |

The six affected-package suites passed 1159 test executions. They reported 15 existing ignored executions; six local interoperability cases were then run explicitly and all six passed. The remaining nine are two Linux L7 capture cases and seven opt-in load/soak cases. None of the new regressions was ignored.

Independent read-only review accepted the rebased composition: bounded SOCKS setup ends before banner/payload processing, final candidate guards precede connect, and LOC-11 permit transfer remains intact. Public API snapshots, architecture/LoC budgets, source contracts and locale gates passed without baseline or dependency changes.

No Rust or Kotlin source changed after this checked commit. This addendum is documentation only. APK/device checks, dedicated Linux capture, load/soak and external compatibility were not run. The pending direct-connect cancellation hypothesis remains owned by the separate LOC-07 task as recorded in the original verification.
