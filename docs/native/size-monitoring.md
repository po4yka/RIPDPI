# Native Size Monitoring

This document describes the current native size policy and attribution workflow.

## Current Policy

- keep the packaged native size verification in CI
- keep the representative `cargo-bloat` regression check in CI
- always upload human-readable reports for both lanes so growth is attributable, not just pass/fail
- defer protocol feature-gating unless the packaged `.so` trend becomes a real release constraint

The user approved the following growth limits on 2026-10-21. All eight measured
library sizes from the approved 2026-09-29 baseline remain unchanged.

| Limit | Previous | Current |
| --- | ---: | ---: |
| Growth of each tracked library and ABI | 393,216 bytes (384 KiB) | 557,056 bytes (544 KiB) |
| Total absolute growth | 1,572,864 bytes (1.5 MiB) | 2,621,440 bytes (2.5 MiB) |
| Total percentage growth | 2.0% | 3.4% |

The total limit is the smaller of the percentage limit, rounded up to the next
byte, and the absolute limit. The recorded baseline total is 76,636,228 bytes.
The 3.4% limit permits 79,241,860 bytes; the absolute limit permits 79,257,668
bytes. Thus, the percentage limit controls total growth. Each tracked library
also has its own 557,056-byte growth limit.

This approved policy change retains the recorded measurements, size checks,
and bloat limits.

### Approval evidence

#### Previous approval: 2026-10-09

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

#### Current approval: 2026-10-21

The user explicitly approved higher native size limits for the BoringSSL 5.3
upgrade in PR #635. [CI run 39552272992](https://github.com/po4yka/RIPDPI/actions/runs/39552272992)
used source commit `d81f28b0963b1d4b43d7e35cfb33e5cc2e1fad1b`.
Its `native-size-report-debug-github` artifact (ID `12325661014`) records
79,182,668 bytes, or 2,546,440 bytes above the retained September baseline.
The largest individual growth is 530,208 bytes for `x86_64/libripdpi.so`.

The previous limits fail for `libripdpi.so` on arm64-v8a, x86, and x86_64,
and for the total. The approved limits leave 59,192 bytes of total headroom
and 26,848 bytes for the largest individual growth. The individual budget
rounds growth up to a 16 KiB boundary, then adds one 16 KiB step for build
variation.
The total percentage budget uses a 0.1 percentage point step. These limits
preserve narrow limits on further growth.

The report ZIP and four native shard ZIPs were downloaded. All five SHA-256
digests matched GitHub artifact metadata. Copies of the shard libraries were
stripped with NDK 29.0.14206865 `llvm-strip --strip-unneeded`. All eight
tracked sizes matched the CI report. The size verifier failed with the
previous policy and passed with the approved policy against these same files.

| Artifact | ID | SHA-256 |
| --- | --- | --- |
| `native-shard-arm64-v8a` | `12324404429` | `29cd6722ae3150156a8b758b0a1945ed96c1078c06569e92660dc960b4c561d5` |
| `native-shard-armeabi-v7a` | `12324866087` | `1b8428cf6a74cfcd7b68fe4225219d937b4fd67a725edfe1eb2afb49a975d501` |
| `native-shard-x86` | `12324691627` | `7a5853e678cf4b1cabfe84c441756fbba17748b351bb2da59fe3185358b2497e` |
| `native-shard-x86_64` | `12323919999` | `18e0ba4716d4a50fcdc38221f4cf2b0623a18821723f5abd19ea14bfac97e784` |
| `native-size-report-debug-github` | `12325661014` | `d0db9ddb7a136b5eeba81a74dd97a03294dc7cae8c4cf4a59b49219fc31e7a08` |

For a PR size-policy update, a repository maintainer must apply the
`approved-native-size-policy` label after explicit user approval. The
exception applies only to `scripts/ci/native-size-baseline.json`. It requires
unchanged recorded library sizes and JSON fields, except that the three
growth limits may increase and `_comment` must append approval provenance.
All other baseline changes remain blocked. Validate the policy guard and
size gate together on the combined PR tree before merge.

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
