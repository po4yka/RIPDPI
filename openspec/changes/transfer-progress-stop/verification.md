---
task_id: DGN-1791521873290293
change: transfer-progress-stop
commit_sha: null
local: passed
local_evidence: Combined-tree Rust suites passed 435 tests. Kotlin passed 45 core tests and 18 UI tests. Full staticAnalysis and app/service locale lint passed after extracting archive object projection into a separate function.
remote_ci: required
remote_ci_evidence: Feature publication is pending; no remote CI success is claimed.
device: blocked
device_evidence: adb devices -l returned no attached devices on 2026-10-09.
artifact: required
artifact_evidence: Host native tests and Kotlin compilation passed. Android native APK build is unverified; local Gradle gates use ripdpi.skipNativeBuild=true.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TRANSFER-BODY | DGN-1791521965388405 | Framing, timing and HTTP classification regression tests | Passed locally |
| REQ-TRANSFER-LIVE | DGN-1791521965388405 | Live state observed during a stalled socket read; cancellation retains partial bytes | Passed locally |
| REQ-TRANSFER-PRESENTATION | DGN-1791521967509403 | Four mapping and three Compose tests; 360 dp and Arabic RTL at font scale 1.5 | Passed locally |
| REQ-TRANSFER-STORAGE | DGN-1791521966410634 | Wire compatibility, strict validation, export and scope persistence tests | Passed locally |

## Local evidence

- Native tests passed: contracts 64, runner 102, monitor 249, runner parity 1,
  contract fixtures 6, and network protocol review 13. Two old trigger-fuzzing
  tests and two opt-in soak tests remain ignored; no new test is ignored.
- The full native command exposed a source-scanner mismatch after the throughput
  function delegated to its live-progress implementation. The scanner now reads
  the emitting function. All six contract fixture tests then passed without a
  fixture change. Doc tests passed.
- Kotlin tests passed: 45 core tests and 18 UI tests, including matrix regression
  checks, strict transfer validation, canonical archive data and atomic scope
  revocation. Rendered images are disposable, not golden replacements.
- Observed RED/GREEN failures cover chunk framing bytes, wire progress loss,
  invalid counters, opaque archive data, network-scope loss, blockpage handling,
  and HTTP failure metadata. UI tests were added after implementation.
- Independent review found two native P2 regressions. Both were fixed with
  regression tests. The final combined review found no remaining P1/P2.
- Architecture health has no new or worsened indicators. Native architecture
  contracts report no violations. Locked Cargo metadata passed.

Remote CI, an Android native APK and live device acceptance are separate gates.
The task stays in review until that evidence is available.

## Final local commands

- `cargo test -p ripdpi-diagnostics-contracts -p ripdpi-diagnostics-runner -p ripdpi-monitor-engine --locked`
  passed the crate suites and parity test. The source-scanner failure was repaired
  and verified with `cargo test -p ripdpi-monitor-engine --test contract_fixtures --locked`.
- The remaining monitor integration targets and the three crates' doc tests passed.
- `./gradlew :core:diagnostics:testDebugUnitTest --tests '*Transfer*' --tests '*SelectiveMatrix*' --tests '*DiagnosticsContractGovernanceTest' --tests '*DiagnosticsOutcomeTaxonomyTest' :app:testGithubFullDebugUnitTest --tests '*DiagnosticsTransfer*' --tests '*DiagnosticsSelectiveMatrix*' -Pripdpi.skipNativeBuild=true --max-workers=2 --offline` passed.
- `./gradlew :core:diagnostics:testDebugUnitTest --tests '*TransferArchiveEvidenceTest' staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug -Pripdpi.skipNativeBuild=true --max-workers=2 --offline` passed.
- `cargo fmt --all --check`, architecture health, native contracts, locked metadata,
  `taskctl verify` and `taskctl validate` passed. No baseline was extended.
