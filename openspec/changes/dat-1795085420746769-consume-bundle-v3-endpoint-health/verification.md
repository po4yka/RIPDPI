---
task_id: DAT-1795085420746769
change: dat-1795085420746769-consume-bundle-v3-endpoint-health
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: No Android device behavior is owned by this task.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this task.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

`local_evidence` stays null until the owner runs the Gradle gates below. All new and changed assertions are Kotlin unit tests, which the implementing session did not compile or run (owner build policy).

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DAT-1795085420746769-001 | DAT-1795085445713982 | `RipdpiBundleContractTest` example and golden-full health and `field_report` assertions, and the `neg-field-report-http-endpoint.json` typed rejection, await the owner Gradle run | required |
| REQ-DAT-1795085420746769-002 | DAT-1795085445713982 | `RipdpiBundleContractTest` `neg-schema-version-2.json` / `neg-missing-schema-version.json` and `SingBoxRipdpiExtensionParserTest` versions 1, 2, 4, 99 assert `UNSUPPORTED_SCHEMA_VERSION`; awaiting the owner Gradle run | required |
| REQ-DAT-1795085420746769-003 | DAT-1795085447701237 | `EndpointHealthOrderTest` (down, expired, degraded, healthy-neutral, all-down) and the `RipdpiBundleContractTest` golden-full and health-expired selector-ordering tests await the owner Gradle run | required |
| REQ-DAT-1795085420746769-004 | DAT-1795085444740643 | Bundle schema, examples, health-expired fixture and `negative/neg-*.json` are byte-identical to deployer commit `e917cf5b` (checked with `cmp`); the `x-contract-version` pin test awaits the owner Gradle run | required |

## Checks run in the implementing session

- `cmp` of every vendored bundle file against `ripdpi-vpn-deploy/.worktrees/field-analytics/contract/`
- `ktlint` 1.8.0 on every changed Kotlin file
- `python3 -m unittest scripts/tests/test_android_migration_fixture.py scripts/tests/test_android_local_acceptance.py scripts/tests/test_android_migration_receipt.py`
- `./taskctl validate`

## Gradle gates left for the owner

```bash
./gradlew :core:data:testDebugUnitTest --tests 'com.poyka.ripdpi.data.RipdpiBundleContractTest' --tests 'com.poyka.ripdpi.data.SingBoxRipdpiExtensionParserTest' --tests 'com.poyka.ripdpi.data.DeviceMigrationContractTest' --tests 'com.poyka.ripdpi.data.PayloadFailoverImportTest' --tests 'com.poyka.ripdpi.data.FleetCompatGoldenFileTest' -Pripdpi.skipNativeBuild=true
./gradlew :core:data:runtime-state:testDebugUnitTest --tests 'com.poyka.ripdpi.data.subscription.EndpointHealthOrderTest' -Pripdpi.skipNativeBuild=true
./gradlew :core:data:testDebugUnitTest :core:data:runtime-state:testDebugUnitTest :app:testGithubDebugUnitTest
./gradlew staticAnalysis
just ci-gates
```

The full `:core:data`, `:core:data:runtime-state` and `:app` unit runs are needed because every inline test bundle moved from `ripdpi.schema_version` 2 to 3.

## Open items

- `core/data/src/test/resources/deployment-generated/metadata.json` records the pre-v3 `payload_sha256`; regenerate the deployment fixtures with the deploy D4 generator.
- Live acceptance against a real v3 bundle needs deploy D4.
