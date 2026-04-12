# Native Size Monitoring

Phase 10 keeps native size visible without forcing premature protocol cuts.

## Current Policy

- keep the packaged native size verification in CI
- keep the representative `cargo-bloat` regression check in CI
- always upload human-readable reports for both lanes so growth is attributable, not just pass/fail
- defer protocol feature-gating unless the packaged `.so` trend becomes a real release constraint

## Reports

The CI workflow now uploads two artifacts on every non-scheduled run:

- `native-size-report`
- `native-bloat-report`

The reports answer two different questions:

- `native-size-report`: how much each shipped Android `.so` grew by ABI and library
- `native-bloat-report`: which crates and representative functions account for the current text-section footprint

## When To Revisit Feature-Gating

Do not gate protocol crates just because dual TLS or QUIC support is visible in the attribution report.

Revisit feature-gating only when at least one of these becomes true:

- packaged native size repeatedly exceeds the checked-in growth budget
- release delivery constraints make APK/App Bundle size a concrete blocker
- attribution reports show a single optional protocol family dominating recent growth with limited product value

Until then, the repository should prefer observability and controlled regressions over architecture churn.
