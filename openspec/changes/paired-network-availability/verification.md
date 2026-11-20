---
task_id: DGN-1795014097920678
change: paired-network-availability
commit_sha: de007a006
local: passed
local_evidence: On fbf450077 (rebased on 6f7ee46b1, macOS) with -Pripdpi.skipNativeBuild=true, staticAnalysis --continue passed; :core:diagnostics:testDebugUnitTest (1843 tests), :core:diagnostics-data:testDebugUnitTest (104 tests) and :app:testGithubDebugUnitTest (3774 tests) passed with 0 failures; :app:lintGithubDebug and :core:service:lintDebug passed; just ci-gates passed (architecture health, LoC and hotspot budgets, contracts, locale parity, translation export, Rust API snapshots).
remote_ci: passed
remote_ci_evidence: CI run 44683066247 on PR #743 head eda0579dc (same tree as merge commit de007a006): ci-required passed with 56 passing checks, including gradle-static-analysis, rust-lint, build-android-debug, build-android-tests and android-instrumentation-apks; 18 nightly or scheduled jobs were skipped, and CodeQL Analyze (java-kotlin) was re-running after a Maven download miss.
device: passed
device_evidence: Pixel 8 Pro, GitHub debug build 2c7a1c168: two selective matrix sessions were paired in History, the comparison rendered and reported the same network type, and the exported comparison JSON was redacted. Not run on device: a pair across different networks (Wi-Fi against LTE); unit tests cover that case.
artifact: passed
artifact_evidence: CI build-android-debug in run 44683066247 assembled :app:assembleGithubDebug with the native libraries, inside the approved 864 KiB per-library growth budget (scripts/ci/native-size-baseline.json, approved 2026-11-19); the device runs used a local GitHub debug APK built from 2c7a1c168.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

Local gates passed on the commit above. No device run and no exact-revision CI run exist yet, so the History card is verified only through Robolectric and a local render. The comparison is descriptive: these tests make no claim about any real network, permitted prefixes, ASN-wide availability, or nationwide results.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PAIRED-NETWORK-AVAILABILITY-PAIR | DGN-1795014099307713 | `SelectiveMatrixPairingTest`: revision mismatch names the target set, a second leg 45 minutes later names the age limit, and legacy sessions on one transport list every mismatch. Room schema 16 migration keeps old sessions unpairable. `HistoryActionsTest` pairs a Wi-Fi and a cellular session in either order, and History offers only sessions with revisions, nearest first. | Passed |
| REQ-PAIRED-NETWORK-AVAILABILITY-CONTROL | DGN-1795014099895795 | `SelectiveMatrixPairingTest`: failed on both legs is inconclusive and outside the denominator, a failed control keeps every row out, incomplete repetitions are unmeasured. The export test decodes per-leg stages, failure stage and attempt counts and the path-specific and comparable counts. | Passed |
| REQ-PAIRED-NETWORK-AVAILABILITY-SCOPE | DGN-1795083687796000 | `SelectiveMatrixPairingTest`: a network change during the second leg invalidates it and shows no path-specific rows. `HistoryActionsTest`: selecting or closing a session clears the pair, and a compare never replaces or drops another selection. The UI only loads stored sessions; it runs no scan and touches no network or VPN state. | Passed |
| REQ-PAIRED-NETWORK-AVAILABILITY-EVIDENCE | DGN-1795083687292151 | `DiagnosticsSelectiveMatrixComposeTest.pairCardShowsPerLegOutcomesAndNamesUnavailableReasons` checks the count, the path-specific row, both per-leg outcomes with repetition counts, and the age-limit reason, with no clipped text. The export test checks that no hostname label, revision, or unkeyed attempt host reaches the JSON. `SelectiveMatrixPlanningTest` checks that the archive redactor drops the target set hash. Strings exist in all 10 locales. | Passed |

## Export

The pair export is its own explicit share (`ripdpi.selective_matrix_pair.v1`, text via the Android share sheet). It carries transport labels, start and finish timestamps, opaque target ids, per-leg stages and repetition counts. It carries no session ids, hostnames, revisions, SIM, SSID, or IP identifiers. The diagnostics archive stays single-session. The wire schema stays at 11 and Room at 16.

## Known limits

- History offers at most six candidate sessions, nearest start time first.
- No Roborazzi baseline covers the new card, and no existing golden changed.
- No RDS deck spec exists for the comparison card; it reuses the selective matrix card primitives.
