---
task_id: TRN-1794371392084141
change: fix-finalmask-audit-findings
commit_sha: 318c9b861d4f0ed8fa5016fb6ef4842f73117a2a
local: passed
local_evidence: Kotlin JUnit 22 tests, full staticAnalysis, current-checkout ci-gates and full locked metadata passed. Earlier native regressions, real peer sessions and independent reviews remain recorded below.
remote_ci: not_applicable
remote_ci_evidence: Local verification owns acceptance; hosted CI is not invoked by this change.
device: not_applicable
device_evidence: No UI layout or device-specific behavior change; no physical probes authorized.
artifact: not_applicable
artifact_evidence: No release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-FINALMASK-FRAGMENT | TRN-1794371455966762 | Rust fragment boundary, allocation and saturating arithmetic regressions; peer fragment sessions | passed |
| REQ-FINALMASK-PASSWORD | TRN-1794371456600683 | Independent Go table digest with edge spaces; two real peer Sudoku sessions | passed |
| REQ-FINALMASK-ERROR | TRN-1794371457144679 | Five bridge tests cover both errors, both half-closes and owner drop; carrier capacity regression | passed |
| REQ-FINALMASK-VALIDATION | TRN-1794371457658854 | Active range limits and inactive-field tests at codec and native admission boundaries | passed |
| REQ-FINALMASK-UNKNOWN | TRN-1794371458138281 | Owning import/service/draft JUnit regressions executed with real settings JSON and production validators; 22 tests passed | passed |
| REQ-FINALMASK-REFERENCE | TRN-1794371458603893 | Exact Xray source plus documented peer adapter; 10 real loopback sessions and real configuration parser; source gates passed | passed |

## Observed checks

The native and peer checks in this section were observed on 2026-11-11 at `cfe9f629be7b6a570aff9a66e975f84bac38b838`, based on main `f4aaf8ce2594843ee53ed1c45d6c082d93131f0d`. The acceptance follow-up below uses the exact source SHA in the front matter. It changes only one Kotlin test fixture; Finalmask production code is unchanged.

From `native/rust`, with the pinned Rust toolchain and `build-gate --lane ripdpi`:

```sh
cargo test --locked -p ripdpi-xhttp -p ripdpi-relay-core finalmask --jobs 2 -- --nocapture
cargo test --locked -p ripdpi-xhttp unpublished_carrier_retains_capacity --jobs 2 -- --nocapture
cargo build --locked -p ripdpi-xhttp --example finalmask_peer --jobs 2
cargo metadata --locked --format-version 1
```

Results: 22 xHTTP/finalmask tests, 7 relay admission tests, and 1 carrier capacity test passed; example build and full metadata resolution exited 0. Filters select the owning regression tests; other suites were not claimed as run. Local log: `/tmp/trn06-rebased-rust.log`.

From the repository root:

```sh
python3 scripts/tests/run-finalmask-peer.py --xray /tmp/trn06-audit/xray-header-fixed --client /Users/npochaev/.cache/cargo-target/debug/examples/finalmask_peer
just ci-gates
```

Both exited 0. The peer run used real Xray v26.10.21 at `1b1647cd9ddf0abc34b981d4611fc03bcac1ca74` plus the committed CloseWrite adapter patch. All five masks passed stream-one and stream-up, with all 256 byte values checked in each session. The runner used only owned loopback listeners and test credentials. Logs: `/tmp/trn06-peer-current.log`, `/tmp/trn06-ci-gates.log`. The source gate includes architecture health, contract checks and Rust API snapshots; no baseline or snapshot was extended.

The independent peer `TestRIPDPIHeaderCloseWrite` failed against stock source because the wrapper lacked CloseWrite, then passed with the patch, including exact EOF on a real TCP half-close. The patch was also applied to a clean pinned source copy. This is a required peer adapter for the documented REALITY header subset, not a stock-upstream compatibility claim. See [compatibility and reproduction commands](../../../../docs/native/finalmask-compatibility.md).

Independent general and async-cancellation reviewers found no remaining actionable issue in the final native, Kotlin and peer changes. Earlier review found a 32-bit overflow risk; saturating arithmetic and the large-range regression resolve it. Commit hooks passed the affected Rust clippy/all-target checks and Kotlin formatting. The exact current Kotlin normalizer was separately extracted with its source constants, compiled by kotlinc and run against unknown, blank/off and all four known types; all checks passed. That check does not replace the owning JUnit suites.

## Resolved blocker and acceptance follow-up

On 2026-11-11, the follow-up at the front-matter SHA passed all remaining Kotlin checks. Its base is main `ec4f13320a272b945bf2a0497d02fcea545d73c9`.

The old online Gradle run failed with `No route to host`; offline resolution then failed on uncached artifacts. Fresh standalone Zulu JDK 21 HTTPS requests now returned 200 for Room compiler 2.8.5 on Google Maven and coroutines on Maven Central. Each current Google IPv4 endpoint also passed TLS/SNI checks. OS proxies were disabled. The exact cause of the old temporary route failure was not established. A normal online Gradle retry downloaded the missing artifacts successfully; no repository URLs, dependency versions, global proxy, truststore or TLS checks were changed. The command used an owned single-use daemon, command-scoped IPv4 selection and bounded HTTP timeouts.

The first JUnit run reached execution and found a bad test fixture: the new import regression put `relayFinalmaskType` inside a nested `relay` object, while `AppSettingsSnapshotSerializer` preserves a flat backup JSON format. Commit `318c9b861d4f0ed8fa5016fb6ef4842f73117a2a` corrects that fixture. Production import already preserves the unknown type with the actual flat contract. The corrected test checks import, model mapping and export/import round-trip.

Run from the repository root with JDK 21:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
build-gate --lane ripdpi -- ./gradlew --no-daemon --continue \
  :core:data:testDebugUnitTest --tests '*AppSettingsJsonTest' \
  :core:service:testDebugUnitTest --tests '*FinalmaskValidationTest' --tests '*FinalmaskCompatibilityTest' \
  :app:testGithubFullDebugUnitTest --tests '*ConnectionViewModelTest.relay validation rejects an unknown finalmask without disabling it' \
  -Pripdpi.skipNativeBuild=true --max-workers=4 \
  -Dorg.gradle.jvmargs='-Xmx5g -XX:MaxMetaspaceSize=1g -Djava.net.preferIPv4Stack=true' \
  -Dorg.gradle.internal.http.connectionTimeout=15000 \
  -Dorg.gradle.internal.http.socketTimeout=30000
build-gate --lane ripdpi -- ./gradlew --no-daemon staticAnalysis \
  -Pripdpi.skipNativeBuild=true --max-workers=4 \
  -Dorg.gradle.jvmargs='-Xmx5g -XX:MaxMetaspaceSize=1g -Djava.net.preferIPv4Stack=true' \
  -Dorg.gradle.internal.http.connectionTimeout=15000 \
  -Dorg.gradle.internal.http.socketTimeout=30000
```

Both commands exited 0. JUnit XML records 15 AppSettingsJsonTest cases, 2 FinalmaskValidationTest cases, 4 FinalmaskCompatibilityTest cases and 1 ConnectionViewModelTest case: 22 passed, zero failures, errors or skips. This is the owning targeted suite, not all repository JUnit tests. The exact `staticAnalysis` aggregate completed in 30m51s: 826 actionable tasks, 509 executed, 30 from cache, 287 up-to-date. It includes both GitHub app variants and the configured detekt, ktlint, Android lint and source checks. Native compilation was omitted for these host Kotlin checks; no APK or device acceptance is claimed. Roborazzi finalization was not needed for the ViewModel assertion and was not used as evidence.

Logs: `/tmp/trn06-acceptance-kotlin-fixed.log`, `/tmp/trn06-acceptance-static.log`. Initial failed fixture run: `/tmp/trn06-acceptance-kotlin.log`. JUnit XML is in each owning module's `build/test-results` directory. Compiler and lint warnings outside this task remain; no quality gate, baseline or suppression was weakened.

Full `cargo metadata --locked --format-version 1` also exited 0 on this checkout. Final current-checkout `build-gate --lane ripdpi -- just ci-gates` exited 0; log `/tmp/trn06-acceptance-ci-gates.log`. All Rust API snapshots matched without updates. The run included a cold BoringSSL build and waited for another build to release the shared Cargo target lock. No foreign process was stopped and no check was omitted. All execution and local acceptance requirements are now satisfied.

## Evidence limits

- C: All six audit fixes are implemented and independently reviewed in separate commits.
- V: The local Rust regressions, real peer sessions and source gates above passed. The 22 owning Kotlin JUnit cases and full staticAnalysis also passed in the acceptance follow-up.
- S: The finalmask reference is pinned separately from the xHTTP v26.2.6 reference; the supported one-layer subset and required peer adapter are explicit.
- E: Only the recorded local Xray source plus adapter was checked. External Cloudflare, physical Android devices, production secrets, root activation, UDP masks, custom Sudoku layouts and TCP segment boundaries were not tested or claimed.
