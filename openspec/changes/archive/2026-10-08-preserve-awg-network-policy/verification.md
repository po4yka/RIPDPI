---
task_id: DAT-1791479988786516
change: preserve-awg-network-policy
commit_sha: 7a4a87a9d90e1575264ad3f3ae643e9580fbec4f
local: passed
local_evidence: 113 targeted policy tests and affected lint passed. The final combined gate passed 269 tests and staticAnalysis on 7a4a87a9d90e1575264ad3f3ae643e9580fbec4f.
remote_ci: not_applicable
remote_ci_evidence: Local fix validation; hosted CI is reported separately after push.
device: not_applicable
device_evidence: The activation policy is tested through Kotlin consumers; no native runtime behavior changes.
artifact: not_applicable
artifact_evidence: No release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-POLICY-MAP | DAT-1791480127619811 | WireGuardActivationMappersTest and service consumer tests | Passed |
| REQ-AWG-POLICY-REFRESH | DAT-1791480127619811 | AwgProfileRepositoryRoomTest | Passed |
| REQ-AWG-POLICY-EMPTY | DAT-1791480128501591 | Parser presence and explicit-empty regression tests | Passed |
| REQ-AWG-POLICY-FAMILY | DAT-1791480128501591 | Runtime readiness, service route/DNS policy, and unchanged CI bundle Simple seeding | Passed |

## Observed checks

- Mapper and refresh RED: 30 tests, 8 expected failures before implementation.
- Family, malformed-policy, and seeder RED: 68 tests, 7 expected failures before implementation.
- GREEN: 69 core data, 10 runtime state, 10 service, and 24 Simple seeder tests passed.
- Affected module ktlint and detekt passed. The parser function limit was preserved by placing the new list guard in its own file.
- Independent review approved the final diff, including malformed JSON and effective family policy.
- The checked-in CI bundle and native runtime were not changed. No device or live traffic claim is made.

## Combined tree verification

The final combined gate passed on `7a4a87a9d90e1575264ad3f3ae643e9580fbec4f`: 269 targeted tests (134 core data, 10 runtime state, 10 service, 24 Simple seeder, and 91 Full app), with zero failures, errors, or skips. The runtime-state tests were up to date on unchanged source. `staticAnalysis` passed in the same invocation: 816 tasks, 167 executed, 5 from cache, and 644 up to date. Architecture health reported 21 unchanged indicators with no new or worsened entries; `cargo metadata --manifest-path native/rust/Cargo.toml --locked` passed. The integration owner supplied these results and the local Gradle log `/tmp/awg-p2-rebased-fresh.log`. Hosted CI, device, native artifact, and live-server results are not inferred.

The previous combined tree `8ff34e99bc60970a77a3d35e6a67aa35f0905b03` also passed 269 targeted tests and `staticAnalysis`. The first run after rebase failed because the disk was full and was followed by heap and result serialization failures. It was superseded by the successful repeat gate above.
