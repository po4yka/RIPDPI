---
task_id: TRN-1792659369802828
change: payload-aware-selector-failover
commit_sha: 5be355fd0397dfea71f7e68ec790a29c568be174
local: passed
local_evidence: "Explicit-file ktlint, git diff --check, taskctl validate and architecture-health passed. Architecture: 21 baseline indicators, zero new, worsened or stale entries. Local Gradle never started because the shared build gate timed out and its wrapper then reported a syntax error; compiler-backed checks are evidenced under remote_ci."
remote_ci: passed
remote_ci_evidence: "Exact source 5be355fd0397dfea71f7e68ec790a29c568be174: CI 39768539174 jobs build-android-tests 119791123903, kotlin-coverage 119791124405, gradle-static-analysis 119790735839, architecture-health 119790735937 and task-contracts 119790640414 passed; fleet-fixtures 39768539085/job119789975065 passed. This is scoped job evidence; the entire source CI run was not yet complete when recorded. Final PR checks remain required before merge."
device: not_applicable
device_evidence: No physical-device or filtered-network acceptance is claimed; existing protected relay lifecycle is reused.
artifact: passed
artifact_evidence: "Exact-source test-results artifact 12421608673 has SHA-256 4f7161b0830e2105bdf0b9a46e723e5593dedff3dcb43f5776d68f74e82b6cf6. Mirrored schema and payload fixture are byte-identical to the coordinated producer; their SHA-256 values are recorded below."
deployment: not_applicable
deployment_evidence: No deployment or live control endpoint is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PAYLOAD-IMPORT | TRN-1792659466914740 | PayloadFailoverImportTest and persistence suites; exact shared schema mirror; JVM job 119791123903 | passed |
| REQ-PAYLOAD-VERIFY | TRN-1792659467442022 | StrictPayloadSocksTest uses real local TLS/SOCKS sockets for complete, truncated, oversized, wrong-hash, redirect, certificate, timeout and cancellation cases; VerifiedPayloadTest covers verifier boundaries; JVM job 119791123903 | passed |
| REQ-PAYLOAD-STABILITY | TRN-1792659467948091 | PayloadFailoverStateTest, SelectorUrltestProberTest and SelectorPayloadQueueTest cover thresholds, cooldown, backoff, bounded traversal, shared capacity and cancellation; Full and Simple suites in job 119791123903 | passed |
| REQ-PAYLOAD-SAFETY | TRN-1792659468436110 | Network/manual fences, no-direct selection, strict policy rejection and SHARE redaction regressions; full affected JVM and static-analysis jobs passed | passed |

## Observed checks and scope

- Local: `ktlint` on changed Kotlin files, `git diff --check`, `./taskctl validate`, and `python3 scripts/ci/check_architecture_health.py` passed.
- Hosted JVM: `./gradlew testDebugUnitTest` with app variants excluded, followed by `:app:testGithubFullDebugUnitTest :app:testGithubSimpleDebugUnitTest`, passed. This includes runtime-state, core-data and core-service suites. Both commands used the existing CI `-Pripdpi.skipNativeBuild=true` setting.
- Hosted `coverageReport` and full `staticAnalysis` passed. No baseline, assertion or rule was weakened.
- Standalone local Detekt was not run: the official pinned CLI download did not complete and one bounded Maven Central attempt timed out. The hosted `staticAnalysis` result is the observed analyzer evidence.
- Local socket fixtures exercise actual HTTPS payload transfer through SOCKS, including positive reserve selection. They do not establish physical-device, native-relay-engine or filtered-network acceptance. The separate Simple embedded-seed route explicitly refuses this policy; supported positive behavior belongs to imported selector groups.

## Shared contract artifacts

- `core/data/src/test/resources/contract/ripdpi-bundle.schema.json`: `bcd0997a270baad54106fa954377e350e99727847dde2cd0fb1cf8d0a2fb63f0`.
- `core/data/src/test/resources/contract/ripdpi-bundle.payload-failover.json`: `bfd3e692ecbbe25994d2ee9fa32422ee9a180f920f8c5c99cc46f62e78dcca16`.

Both files were compared byte-for-byte with the coordinated producer after its final URL-validator adjustment. No deployment or live control endpoint was exercised.
