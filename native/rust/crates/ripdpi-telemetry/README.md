# ripdpi-telemetry

**Layer:** L2 -- contracts / config.

`ripdpi-telemetry` defines shared native telemetry data structures consumed by runtime, diagnostics, and Android projection adapters.

## Boundaries

- Data contracts and lightweight telemetry types belong here.
- Logging sinks, Android presentation, and high-volume runtime control loops belong in downstream crates.

## Checks

Run focused checks with `cargo test -p ripdpi-telemetry`.
