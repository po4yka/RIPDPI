---
task_id: DAT-1791479988786516
change: preserve-awg-network-policy
commit_sha: null
local: required
local_evidence: 113 mapper, parser, Room, runtime, service, and Simple seeder tests passed; affected ktlint and detekt passed. Final combined-tree tests and staticAnalysis pending.
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
