---
task_id: TRN-1794371392084141
change: fix-finalmask-audit-findings
commit_sha: cfe9f629be7b6a570aff9a66e975f84bac38b838
local: blocked
local_evidence: Rust 30 tests, real Xray 10 sessions, ci-gates, full locked metadata and independent review passed; full Kotlin JUnit and staticAnalysis are blocked by uncached Maven dependencies and failed network access.
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
| REQ-FINALMASK-UNKNOWN | TRN-1794371458138281 | Exact source normalizer compiled and passed; owning import/service/draft JUnit regressions added but blocked | blocked |
| REQ-FINALMASK-REFERENCE | TRN-1794371458603893 | Exact Xray source plus documented peer adapter; 10 real loopback sessions and real configuration parser; source gates passed | passed |

## Observed checks

Checked on 2026-11-11 at the source SHA above, after rebasing onto main `f4aaf8ce2594843ee53ed1c45d6c082d93131f0d`. Later changes to this record do not change the tested application inputs.

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

## Blocked checks and next action

The required full Kotlin tests did not reach test execution. Online Gradle dependency resolution failed with `No route to host`; offline resolution failed because dependencies such as Room compiler 2.8.5 were not cached. Public repository HTTP checks succeeded separately, so this does not prove an unavailable artifact. A temporary loopback proxy retry also failed and was stopped. No repository versions, global proxy, trust or security settings were changed.

Re-run with JDK 21 after Gradle repository access is restored:

```sh
build-gate --lane ripdpi -- ./gradlew \
  :core:data:testDebugUnitTest --tests '*AppSettingsJsonTest' \
  :core:service:testDebugUnitTest --tests '*FinalmaskValidationTest' --tests '*FinalmaskCompatibilityTest' \
  :app:testGithubFullDebugUnitTest --tests '*ConnectionViewModelTest.relay validation rejects an unknown finalmask without disabling it' \
  -Pripdpi.skipNativeBuild=true --max-workers=4 \
  -Dorg.gradle.jvmargs='-Xmx5g -XX:MaxMetaspaceSize=1g'
build-gate --lane ripdpi -- ./gradlew staticAnalysis \
  -Pripdpi.skipNativeBuild=true --max-workers=4 \
  -Dorg.gradle.jvmargs='-Xmx5g -XX:MaxMetaspaceSize=1g'
```

The final `staticAnalysis` attempt used `--offline` and exited 1 during dependency resolution, before analysis. Missing cached artifacts included detekt and Android lint dependencies. Logs: `/tmp/trn06-kotlin-red-network.log`, `/tmp/trn06-kotlin-red-offline.log`, `/tmp/trn06-static-analysis.log`. F5 remains unchecked for its owning JUnit execution; F6 remains unchecked for its combined-gates clause even though its peer/reference requirement passed. Do not archive or close this task while local acceptance is blocked.

## Evidence limits

- C: All six audit fixes are implemented and independently reviewed in separate commits.
- V: The local Rust regressions, real peer sessions and source gates above passed. Full Kotlin execution remains blocked.
- S: The finalmask reference is pinned separately from the xHTTP v26.2.6 reference; the supported one-layer subset and required peer adapter are explicit.
- E: Only the recorded local Xray source plus adapter was checked. External Cloudflare, physical Android devices, production secrets, root activation, UDP masks, custom Sudoku layouts and TCP segment boundaries were not tested or claimed.
