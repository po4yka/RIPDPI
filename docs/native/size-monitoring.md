# Native Size Monitoring

This document describes the current native size policy and attribution workflow.

## Current Policy

- keep the packaged native size verification in CI
- keep the representative `cargo-bloat` regression check in CI
- always upload human-readable reports for both lanes so growth is attributable, not just pass/fail
- defer protocol feature-gating unless the packaged `.so` trend becomes a real release constraint

The user approved the following growth limits on 2026-11-06. All eight measured
library sizes from the approved 2026-09-29 baseline remain unchanged.

| Limit | Previous | Current |
| --- | ---: | ---: |
| Growth of each tracked library and ABI | 606,208 bytes (592 KiB) | 671,744 bytes (656 KiB) |
| Total absolute growth | 3,407,872 bytes (3.25 MiB) | 3,670,016 bytes (3.5 MiB) |
| Total percentage growth | 4.5% | 4.7% |

The total limit is the smaller of the percentage limit, rounded up to the next
byte, and the absolute limit. The recorded baseline total is 76,636,228 bytes.
The 4.7% limit permits 80,238,131 bytes; the absolute limit permits 80,306,244
bytes. Thus, the percentage limit controls total growth. Each tracked library
also has its own 671,744-byte growth limit.

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

#### Previous approval: 2026-10-21

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

#### Previous approval: 2026-10-26

The user approved higher limits for the current client CI prerequisites in
PR #669. [CI run 40271295423](https://github.com/po4yka/RIPDPI/actions/runs/40271295423)
used commit `7e5559d6985dbd11f1a69c57752fdbac0c8ce376`. The report records
79,417,916 bytes, or 2,781,688 bytes above the retained September baseline.
The largest individual growth is 577,248 bytes.

The five downloaded ZIP digests matched GitHub artifact metadata. All eight
tracked libraries, stripped with NDK 29.0.14206865 `llvm-strip --strip-unneeded`,
matched the report. The verifier failed with the previous policy and passed
with the approved policy against these same files. No recorded size changed.

The individual budget rounds growth up to a 16 KiB boundary, then adds one
16 KiB step. The total budgets round growth up to 0.1 percentage point and
0.25 MiB boundaries. The new limits leave 28,960 bytes for the largest
individual growth and 53,853 bytes for the total.

| Artifact | ID | SHA-256 |
| --- | --- | --- |
| `native-size-report-debug-github` | `12629556128` | `eb04afe4f0419ab6f06c89d3e6b2bcfa8ebefe56ec752324e388ef6538679a87` |
| `native-shard-armeabi-v7a` | `12628963752` | `405541a6e6d2c4a278648090055d1e55b99c63a76df1698bd08b5cd1fd327ed1` |
| `native-shard-x86_64` | `12628514483` | `1dc557b6c5a3b2014f00b9e8138d7d1e4381e0ba7a53909cdbf8c3eb4a6e6800` |
| `native-shard-x86` | `12628507549` | `9faf2c9a204b4b9bae96cffa874639a65d7d3e54e1601f06015a9089c778de9d` |
| `native-shard-arm64-v8a` | `12627958042` | `0d69537d0a8867ed4d85f4bdf2724ff539809263627bacf1c399cadaf9d167de` |

#### Previous approval: 2026-10-28

The user approved total growth limits of 4.5% and 3.25 MiB against
[CI run 40549765242](https://github.com/po4yka/RIPDPI/actions/runs/40549765242),
commit `de2a76cbc`. The recorded total growth was 3,079,500 bytes.
The 592 KiB per-library limit and all September measurements were retained.

#### Current approval: 2026-11-06

The user approved higher native size limits to unblock CI for the settings
JSON migration. [CI run 42198808996](https://github.com/po4yka/RIPDPI/actions/runs/42198808996)
used source commit `83ec7d0ddb8b63899700e6213ebf6d4233a449cd`. Its size report
records 80,223,672 bytes, or 3,587,444 bytes above the retained September
baseline. The largest individual growth is 647,264 bytes for
`x86_64/libripdpi.so`.

All five downloaded ZIP digests matched GitHub artifact metadata. Copies of
the four native shards were stripped with NDK 29.0.14206865
`llvm-strip --strip-unneeded`. All eight tracked sizes matched the CI report.
The unchanged size verifier failed under the previous policy for three
`libripdpi.so` entries and the total. It passed under the approved policy
against the same files. The ELF verifier passed for all five libraries on
all four ABIs.

The individual budget rounds the largest growth up to a 16 KiB boundary,
then adds one 16 KiB step. The total budgets round growth up to 0.1 percentage
point and 0.25 MiB boundaries. These limits leave 24,480 bytes for the largest
individual growth and 14,459 bytes for the total. All recorded measurements,
verifier rules, tracked libraries, and bloat limits remain unchanged.

| Artifact | ID | SHA-256 |
| --- | --- | --- |
| `native-size-report-debug-github` | `13495452552` | `4c1c09c7c127e34db7eaee8a160d2a419895865e642fe268a3d452b8c77b0071` |
| `native-shard-armeabi-v7a` | `13495186222` | `f7bf1e042bde8dd3c6afed2bdd0bffa64e449ac9399c15cb00f79f696ea92da0` |
| `native-shard-arm64-v8a` | `13494783275` | `716bd8782185876cd59e67203711596a97e4b5ba29a7f00497b2047ef25a2892` |
| `native-shard-x86` | `13494782507` | `2995a84f8a736be9b0622083254adab3329be6ace9df70f3b5ab71940855a17c` |
| `native-shard-x86_64` | `13494532715` | `d3860c9bfeb46f0bbfb6d1b4ec2a96bf434431eca60df384dbc0cf8d48b179c3` |

#### Geo snapshot validation: 2026-11-06

The limits in `main` already match this approval. No further policy update
is required for the Geo snapshot change.

The user explicitly approved the three limits above and the
`approved-native-size-policy` label for PR #710.
[CI run 42192916752](https://github.com/po4yka/RIPDPI/actions/runs/42192916752)
used source commit `80beb84ac7f380ed9694c4c92e2d5eeb8af34332`. Its report records
80,230,800 bytes, or 3,594,572 bytes above the retained September baseline.
The previous policy fails for `libripdpi.so` on arm64-v8a, x86, and x86_64,
and for the total.

| ABI | Library | Recorded baseline | Measured size |
| --- | --- | ---: | ---: |
| arm64-v8a | `libripdpi.so` | 11,518,392 | 12,138,856 |
| arm64-v8a | `libripdpi-tunnel.so` | 8,834,528 | 9,161,976 |
| armeabi-v7a | `libripdpi.so` | 8,056,932 | 8,498,408 |
| armeabi-v7a | `libripdpi-tunnel.so` | 6,015,696 | 6,231,892 |
| x86 | `libripdpi.so` | 11,225,552 | 11,871,924 |
| x86 | `libripdpi-tunnel.so` | 8,181,064 | 8,528,744 |
| x86_64 | `libripdpi.so` | 12,953,352 | 13,600,856 |
| x86_64 | `libripdpi-tunnel.so` | 9,850,712 | 10,198,144 |

The individual limit rounds the largest measured growth, 647,504 bytes, up
to a 16 KiB boundary and adds one 16 KiB step. The total limits round growth
up to 0.1 percentage point and 0.25 MiB boundaries. The approved policy leaves
24,240 bytes for the largest individual growth and **7,331 bytes in total**.
The total reserve is small; a later change can still fail this gate.

The size failure predates the Geo snapshot change. On arm64-v8a, the last
passing report at `a8419a7` recorded 12,070,456 bytes for `libripdpi.so`.
The HTTP framing migration at `067ba0978` increased this to 12,127,688 bytes,
which exceeded the previous 12,124,600-byte limit. That migration replaced
private HTTP decoding with `hyper` and `hyper-util` in the Apps Script,
diagnostics, and DNS paths.

The Geo snapshot change adds 7,128 bytes across the eight compared outputs.
The comparison uses base `da1d0051866644cc29777c55bef5cb0701fad647` for arm64-v8a,
armeabi-v7a, and x86, and `fe038f283d6a07c4e9a1aa7b14b3602670f7cee6` for x86_64.
The latter precedes the vendor-format-only commit `da1d0051866644cc29777c55bef5cb0701fad647`.
The corresponding CI runs are
[42191269433](https://github.com/po4yka/RIPDPI/actions/runs/42191269433) and
[42189829716](https://github.com/po4yka/RIPDPI/actions/runs/42189829716).

The four current native shard ZIP digests matched GitHub artifact metadata.
All eight extracted libraries matched the current size report. The size
verifier failed with the previous policy and passed with the approved policy
against these same real library files.
No library file or recorded baseline measurement was changed.

| Artifact | ID | SHA-256 |
| --- | --- | --- |
| `native-size-report-debug-github` | `13491719738` | `5a53dbc8dd8f910fe9eeee1f466c4512249b8365a29157d1f1e2ee8c330eebe5` |
| `native-shard-arm64-v8a` | `13492200045` | `f1ff9d4d1cf8eb8aba0d9e3da5094ba18d56d158462861c51dc4612135336f4c` |
| `native-shard-armeabi-v7a` | `13492165455` | `29eb4650802d23d1269626d854db414f368f5905ae9b66182ac3347e4fd1bdbb` |
| `native-shard-x86` | `13491852030` | `db67e44a7182c35ef0561c3dc77a82b02456fc9c2d59420c55bbf4d14210fba5` |
| `native-shard-x86_64` | `13491502537` | `218a74bb644f82f17f0dbff15208dc5c95fd1ff50fc9321067c28dbb9048ab00` |

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
