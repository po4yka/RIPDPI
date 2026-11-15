---
task_id: DGN-1794747077897183
change: correct-dia17-measurements
commit_sha: 023db7f5fc1452d62bc1a1330597043bcf7f3aea
local: passed
local_evidence: 318 core cases and 90 Detection UI cases passed. Lint retry, Detection Detekt, all source gates and full staticAnalysis passed. Final rebase preserves all JVM inputs; architecture health and locked metadata passed.
remote_ci: required
remote_ci_evidence: Exact published main CI remains unverified. No green CI claim is made.
device: not_applicable
device_evidence: Host process and loopback acceptance only. Android and OEM compatibility remain unverified.
artifact: not_applicable
artifact_evidence: No release binary or golden baseline is requested.
deployment: not_applicable
deployment_evidence: Git publication only. No service deployment is requested.
---

# Verification

Portfolio: DGN-1794747077897183. Audit: /Users/npochaev/Downloads/RIPDPI_network_audits/DIA-17.md.
The original audit and registry are not edited by this repair.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIA17-F1 | DGN-1794747257208254 | DetectionResultPresenterTest verifies default, disabled and enabled network permission. | passed |
| REQ-DIA17-F2 | DGN-1794747257764556 | DetectionMeasurementStatusTest, DetectionHistorySerializationTest, DetectionAuxStateOwnerTest, CommunityComparisonClientTest, DetectionGeneratedLocaleTest and DetectionScreenSemanticsTest verify unknown, error and skipped results through exports, history, statistics and UI. | passed |
| REQ-DIA17-F3 | DGN-1794747258258394 | StealthScoreTest separates timing observations from local VPN capability facts and preserves strong evidence. | passed |
| REQ-DIA17-F4 | DGN-1794747258765077 | RttTriangulationCheckerTest and SystemPingProberTest cover quorum, replied finite samples, missing jitter, partial loss, packet counts and malformed output. | passed |
| REQ-DIA17-F5 | DGN-1794747259325105 | TimingAnalysisCheckerTest uses loopback sockets and delayed resolution to verify the connect-only clock boundary, pinned address and LOW review evidence. | passed |
| REQ-DIA17-F6 | DGN-1794747259831498 | SystemPingProberTest and DetectionHostResolverTest cover process timeout, cancellation, reap, bounded output and the two-lookup bound. | passed |
| REQ-DIA17-F7 | DGN-1794747260358533 | SystemPingProberTest verifies capability-selected iputils/toybox numeric arguments and unavailable unknown profiles with local executable fixtures. | passed |

## Commits

| Finding | Code commit |
|---|---|
| F1 | b71c7a0996 |
| F2 | 2af0d2e999 |
| F3 | cd4753fb18 |
| F4 | 023db7f5fc |
| F5 | 17bfb71cba |
| F6 | d1738f2973 |
| F7 | 420992c78b |

## Observed checks

Before rebase, 318 core cases in 54 suites and 90 app Detection cases in 14 suites passed with zero failures, errors or skips. Detection Detekt, app/core/service lint and just ci-gates passed. App Detekt reported seven issues in three unchanged diagnostics files. No baseline or suppression was changed.

After rebase onto 3ef8328d12031bbacb34be1c897ba142dafd0724, the same 318 core and 90 app cases passed. These are normal incremental Gradle invocations, not a claim that every upstream task ran without a cache. The first combined lint invocation failed inside Kotlin FIR while analyzing XrayApiClientParsingTest.kt. Its Git blob is identical to origin/main. The cause is not established. A bounded retry uses a separate Gradle process without configuration cache.

The independent reviewer cleared the behavior fixes and the final Inconclusive badge change. After rebase, the reviewer verified unchanged owning source/test files and all ten locale merges. Each locale adds only the two DIA-17 keys; all upstream entries are preserved. The incomplete summary and category-state bitmap renders were inspected. These are host UI renders, not device acceptance or golden blessings.

Locked Cargo metadata succeeded with 113 workspace members. Task contracts and strict OpenSpec validation passed. Final combined phases passed: lint retry exit 0 (262.2 seconds), just ci-gates exit 0 (73.4 seconds), and full staticAnalysis exit 0 (268.3 seconds). The seven earlier app Detekt issues no longer occur on the incoming main tree. The initial FIR failure remains in its log; the bounded retry passed without a source, baseline or dependency change.

The last rebase includes only upstream commit 89d4be0579e82b46a7f0f43a53ad138b26e64054, which moves Rust DoQ test fixtures. The diff from the tested code SHA 471e3c46679eabf9f2e654c30d1270938f51158b to the final code SHA contains only those two Rust paths. App, core, resources, Gradle and build-logic inputs are byte-identical. final-rebase-proof.json records this comparison. Final architecture health passed with zero new or worsened indicators; locked metadata passed. JVM checks remain applicable without another unchanged-input run. Main was fast-forwarded to the final code SHA before this verification record was committed.

## Exact commands and evidence

Evidence root: /Users/npochaev/.local/share/ripdpi-dia17-repair-evidence/. The rebased subdirectory contains code-sha.txt, junit-summary.json, cargo-metadata.json, lint-inherited-proof.json, per-phase logs and JSON exit ledgers. Earlier failures and pre-rebase results remain in the parent directory.

All heavy checks run under build-gate --lane ripdpi. The exact sequential phase arguments and exits are in verification-results.json and continuation-results.json. The commands are:

```text
./gradlew :core:detection:testDebugUnitTest --max-workers=4 -Pripdpi.nativeCpuBudget=2 -Pripdpi.skipNativeBuild=true
./gradlew :app:testGithubFullDebugUnitTest --tests com.poyka.ripdpi.ui.screens.detection.* --max-workers=4 -Pripdpi.nativeCpuBudget=2 -Pripdpi.skipNativeBuild=true
./gradlew :core:detection:detekt :app:lintGithubFullDebug :core:detection:lintDebug :core:service:lintDebug --max-workers=4 -Pripdpi.nativeCpuBudget=2 -Pripdpi.skipNativeBuild=true
```

The lint retry appends --no-daemon --no-configuration-cache --continue. The continuation also runs:

```text
CARGO_BUILD_JOBS=4 just ci-gates
./gradlew staticAnalysis --max-workers=4 -Pripdpi.nativeCpuBudget=2 -Pripdpi.skipNativeBuild=true --no-daemon --no-configuration-cache --continue
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1
./taskctl validate
./taskctl openspec cli validate correct-dia17-measurements --strict
```

The native build is skipped for JVM checks. No Android binary or native transport acceptance is claimed.

## Evidence limits

- C covers the repaired source, callers and independent review. V covers only the named JVM, process, loopback and host UI checks. The original audit holds the S evidence; this repair does not raise S or E from static analysis or local fixtures.
- Local executable fixtures verify process and parser contracts. They do not prove real Android ICMP wire interoperability. Android toybox/iputils and OEM failures need separately authorized device acceptance.
- No external server probe, physical device run, root activation, real secret, golden blessing, dependency change or baseline change was used.
- A canceled native DNS lookup can finish later. At most two blocking lookups retain worker slots; callers time out or cancel. Cancellation during DNS was tested. Cancellation during a blocked real TCP connect has source review but no runtime acceptance.
- ICMP remains internal opt-in. IPv6 Echo, a new activation UI and H1-H4/G1 from the audit are not added as confirmed defects. Country/roaming calibration and real-network false-positive rates remain unverified.
- Legacy history has no stored completeness evidence. It retains its old interpretation. New incomplete records carry an explicit flag and are excluded from local aggregate statistics.
- The task stays in review while required quality or exact published-commit CI evidence is unresolved. No task closure or OpenSpec archive is claimed.
