# ripdpi-shared-priors

**Layer:** L3 -- domain logic.

`ripdpi-shared-priors` verifies signed shared-priors bundles and manages the process-wide priors registry consumed by strategy evolution.

## Boundaries

- Bundle verification and registry mechanics belong here.
- Strategy scoring and selection belong in `ripdpi-runtime-strategy`; network fetching or Android asset policy belongs outside this crate.

## Checks

Run focused checks with `cargo test -p ripdpi-shared-priors`.
