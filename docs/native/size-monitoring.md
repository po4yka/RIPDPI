# Native Size Monitoring

This document describes the current native size policy and attribution workflow.

## Current Policy

- keep the packaged native size verification in CI
- keep the representative `cargo-bloat` regression check in CI
- always upload human-readable reports for both lanes so growth is attributable, not just pass/fail
- defer protocol feature-gating unless the packaged `.so` trend becomes a real release constraint

The user approved the following growth limits on 2026-10-09. All eight measured
library sizes from the approved 2026-09-29 baseline remain unchanged.

| Limit | Previous | Current |
| --- | ---: | ---: |
| Growth of each tracked library and ABI | 131,072 bytes (128 KiB) | 393,216 bytes (384 KiB) |
| Total absolute growth | 262,144 bytes (256 KiB) | 1,572,864 bytes (1.5 MiB) |
| Total percentage growth | 2.0% | 2.0% |

The total limit is the smaller of the percentage limit, rounded up to the next
byte, and the absolute limit. The recorded baseline total is 76,636,228 bytes.
The 2.0% limit permits 78,168,953 bytes; the absolute limit permits 78,209,092
bytes. Thus, the percentage limit still controls total growth. Each tracked
library also has its own 393,216-byte growth limit.

This is an approved policy change. It does not replace the recorded measurements
with current artifact sizes, remove a check, or change the bloat limits.

### Approval evidence

[CI run 37927644982](https://github.com/po4yka/RIPDPI/actions/runs/37927644982)
used source commit `6ce2271e7b33f813e098e969b74d191e663b0b61`. Its
`native-size-report-debug-github` artifact (ID `11615667424`) records a total
size of 78,098,812 bytes, which is 1,462,584 bytes above the September baseline.
The report and four native shard archives were downloaded. Their SHA-256
digests matched GitHub artifact metadata. All eight tracked library sizes in
the archives matched the report.

The size verifier was run against these real stripped libraries with both
policies. The previous policy failed for `libripdpi.so` on all four ABIs and for
the total. The approved policy passed for all eight entries and the total.
It leaves 70,141 bytes of total headroom. The largest individual growth was
391,144 bytes for `x86_64/libripdpi.so`; its remaining headroom is 2,072 bytes.
Further growth can still fail either limit.

### Coverage

The size gate tracks `libripdpi.so` and `libripdpi-tunnel.so` on `arm64-v8a`,
`armeabi-v7a`, `x86`, and `x86_64`. It does not track the packaged relay, WARP,
or AmneziaWG libraries, or the root-helper executable. A passing size report
does not establish size coverage for those outputs.

The ELF gate checks all five packaged libraries; dependency oracles apply only
to the libraries listed in its `EXPECTED_NEEDED` map. The representative bloat
gate checks only `ripdpi-android` and `ripdpi-tunnel-android`. Relay, WARP, and
AmneziaWG bloat remain outside that gate. These scopes are separate from the
size policy.

### Change the policy without replacing measurements

Use `scripts.ci.verify_native_sizes.build_baseline_payload` with the recorded
`libraries` map and the approved limits. Preserve the baseline provenance in
`_comment` and add the threshold approval date and source run. Review the diff:
only the growth limits and policy attribution must change. Do not use
`--dump-current` for a threshold-only change; it replaces the measurements.

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
