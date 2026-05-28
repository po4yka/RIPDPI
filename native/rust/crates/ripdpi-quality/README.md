# ripdpi-quality

**Layer:** L3 -- domain logic.

`ripdpi-quality` provides bounded rolling-window quality telemetry primitives for runtime health and degradation reporting.

## Boundaries

- Rolling metrics and snapshot types belong here.
- UI presentation, Android persistence, and runtime control decisions belong in downstream crates.

## Checks

Run focused checks with `cargo test -p ripdpi-quality`.
