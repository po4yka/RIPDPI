---
task_id: DAT-1795085420746769
change: dat-1795085420746769-consume-bundle-v3-endpoint-health
commit_sha: d9aa00b99ffd01d299c33993874b39d7b3b98d72
local: passed
local_evidence: 'Owner-authorized Gradle run on 2026-11-20 at dc1bc3d74: unit tests of :core:data, :core:data:model, :core:data:runtime-state, :core:diagnostics-data, :core:detection, :core:diagnostics, :core:service and :app (testGithubDebugUnitTest), build-logic :convention:test, :app:verifyRoborazziGithubDebug, staticAnalysis, just ci-gates and taskctl validate passed (exit 0).'
remote_ci: passed
remote_ci_evidence: 'CI workflow run https://github.com/po4yka/RIPDPI/actions/runs/46009834608 succeeded on exact main SHA d9aa00b99ffd01d299c33993874b39d7b3b98d72: 35 successful jobs and 31 change-routed skips, no failures, including kotlin-coverage, verify-roborazzi and android-instrumented-tests on API 27, 33, 35, 36 and 37. Rust workspace tests skipped there (no Rust change since); they succeeded in run https://github.com/po4yka/RIPDPI/actions/runs/45985235551 on ancestor a0714e6e37.'
device: not_applicable
device_evidence: No Android device behavior is owned by this task.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this task.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Gradle run (owner-authorized, 2026-11-20)

The owner authorized Gradle on 2026-11-20. At `dc1bc3d74` every gate below exited 0; logs are in `/private/tmp/claude-501/-Users-po4yka-GitRep/77ff58fa-9fb9-4fb3-ac83-38ef9dcd63ce/scratchpad/runs-gradle/`.

| Gate | Log |
|---|---|
| `:core:data:model`, `:core:data:runtime-state`, `:core:data`, `:core:diagnostics-data`, `:core:detection` `testDebugUnitTest` | `test-1.log` |
| `:core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest :app:testGithubDebugUnitTest` | `final-unit.log` |
| `-p build-logic :convention:test` | `final-convention.log` |
| `:app:verifyRoborazziGithubDebug -Pripdpi.includeRoborazziUnitTests=true --tests 'com.poyka.ripdpi.ui.screenshot.*'` | `final-roborazzi.log` |
| `staticAnalysis` | `final-static.log` |
| `just ci-gates`, `./taskctl validate` | `final-ci-gates.log`, `final-taskctl.log` |

The first run failed 17 tests, all caused by this branch; the fixes are `dd0c9402a` (field-report hour order), `d8ee5a883` (shared `RipDpiJson`), `6f877632e` (tests for the new data-transparency string and the `failureStage` probe parameter), `2a690f854` and `6a10104a9`, `9b8104b6f`, `fd0ac02c9` (lint and detekt, no baseline change). Blessed with owner authorization: `8b18b1e22` (`integrity_v12.json`, only the new `collectionStatus` field) and `dc1bc3d74` (two Roborazzi baselines: the data-transparency bullet and the onboarding chip). Rows above that say a test awaits the owner Gradle run are covered by this run.

Before the owner-authorized Gradle run above, every new and changed assertion except the vendored-contract drift check is a Kotlin unit test, which no session compiled or ran (owner build policy).

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DAT-1795085420746769-001 | DAT-1795085445713982 | `RipdpiBundleContractTest` example and golden-full health and `field_report` assertions, the `neg-field-report-http-endpoint.json` typed rejection, and the trailing-newline tests (field_report endpoint and cohort_token refused, server_ref health entry dropped) await the owner Gradle run | required |
| REQ-DAT-1795085420746769-002 | DAT-1795085445713982 | `RipdpiBundleContractTest` `neg-schema-version-2.json` / `neg-missing-schema-version.json` and `SingBoxRipdpiExtensionParserTest` versions 1, 2, 4, 99 assert `UNSUPPORTED_SCHEMA_VERSION`; awaiting the owner Gradle run | required |
| REQ-DAT-1795085420746769-003 | DAT-1795085447701237 | `EndpointHealthOrderTest` (down, expired, degraded, healthy-neutral, all-down fail-open, stored health re-evaluated after ttl), `RipdpiBundleContractTest` (stored members with health, golden-full and health-expired selection, all-down import with `AllDownFailOpen`), `SelectorUrltestProberTest` (down member excluded until its ttl expires, all-down keeps failover on all members) and `SubscriptionMemberPersistenceTest` (refresh replaces health) await the owner Gradle run | required |
| REQ-DAT-1795085420746769-004 | DAT-1795085444740643 | `python3 scripts/ci/check_vendored_deploy_contracts.py --source <ripdpi-vpn-deploy>/contract` exit 0 against deployer commit `e84aec57d824` (log `/private/tmp/claude-501/-Users-po4yka-GitRep/77ff58fa-9fb9-4fb3-ac83-38ef9dcd63ce/scratchpad/runs-r2r4-fix/vendored-contracts-check.log`); `python3 -m unittest scripts.tests.test_vendored_deploy_contracts` 5 passed (log `/private/tmp/claude-501/-Users-po4yka-GitRep/77ff58fa-9fb9-4fb3-ac83-38ef9dcd63ce/scratchpad/runs-r2r4-fix/py-vendored-contracts.log`); the `x-contract-version` pin test awaits the owner Gradle run | required |

## Checks run in the implementing sessions

- `python3 scripts/ci/check_vendored_deploy_contracts.py --source /Users/po4yka/GitRep/ripdpi-vpn-deploy/.worktrees/field-analytics/contract`: exit 0
- `python3 -m unittest scripts.tests.test_vendored_deploy_contracts`: 5 passed, exit 0
- `ktlint` 1.8.0 on every changed Kotlin file: exit 0 (log `/private/tmp/claude-501/-Users-po4yka-GitRep/77ff58fa-9fb9-4fb3-ac83-38ef9dcd63ce/scratchpad/runs-r2r4-fix/ktlint.log`)
- `python3 -m unittest scripts/tests/test_android_migration_fixture.py scripts/tests/test_android_local_acceptance.py scripts/tests/test_android_migration_receipt.py` (first session)
- `./taskctl validate` and `just task-check`: exit 0 (logs `/private/tmp/claude-501/-Users-po4yka-GitRep/77ff58fa-9fb9-4fb3-ac83-38ef9dcd63ce/scratchpad/runs-r2r4-fix/taskctl-validate.log`, `/private/tmp/claude-501/-Users-po4yka-GitRep/77ff58fa-9fb9-4fb3-ac83-38ef9dcd63ce/scratchpad/runs-r2r4-fix/task-check.log`)

## Gradle gates

```bash
./gradlew :core:data:testDebugUnitTest --tests 'com.poyka.ripdpi.data.RipdpiBundleContractTest' --tests 'com.poyka.ripdpi.data.SingBoxRipdpiExtensionParserTest' --tests 'com.poyka.ripdpi.data.DeviceMigrationContractTest' --tests 'com.poyka.ripdpi.data.PayloadFailoverImportTest' --tests 'com.poyka.ripdpi.data.FleetCompatGoldenFileTest' -Pripdpi.skipNativeBuild=true
./gradlew :core:data:runtime-state:testDebugUnitTest --tests 'com.poyka.ripdpi.data.subscription.EndpointHealthOrderTest' -Pripdpi.skipNativeBuild=true
./gradlew :app:testGithubDebugUnitTest --tests 'com.poyka.ripdpi.subscription.SelectorUrltestProberTest' --tests 'com.poyka.ripdpi.subscription.SubscriptionMemberPersistenceTest' -Pripdpi.skipNativeBuild=true
./gradlew :core:data:testDebugUnitTest :core:data:runtime-state:testDebugUnitTest :app:testGithubDebugUnitTest
./gradlew staticAnalysis
just ci-gates
```

The full `:core:data`, `:core:data:runtime-state` and `:app` unit runs are needed because every inline test bundle moved from `ripdpi.schema_version` 2 to 3, `ProxyGroup` gained `memberHealth`, and an all-down bundle now imports instead of failing.

## Open items

- `core/data/src/test/resources/deployment-generated/metadata.json` records the pre-v3 `payload_sha256`; regenerate the deployment fixtures with the deploy D4 generator.
- Live acceptance against a real v3 bundle needs deploy D4.

## Review fixes

Findings of the independent review of `fd922df2b..HEAD`. Each Kotlin regression test below was written to fail before its fix; none was compiled or run (owner build policy: no Gradle).

| Finding | Fix and regression test | Result |
|---|---|---|
| M4 fail-open judged before the Cloudflare filter | With every direct member down and a Cloudflare member up, health ranking returned only the Cloudflare member, which the pass then filtered out, leaving an empty round. The prober now ranks only the probe-eligible members, so they fail open. `SelectorUrltestProberTest` `direct members all down fail open while a Cloudflare member is up` | written, owner Gradle run pending |
| M5 degraded-last on latency ties | Verified, no fix needed: the reachability pass measures members in health order and `bestSwitchCandidate` takes the first minimum, so a neutral member wins a tie over a degraded one; the payload pass takes the first qualifying reserve in the same order. `SelectorUrltestProberTest` `a neutral member wins a latency tie with a degraded member declared first` pins it (it passes before and after this review, by design) | verified by reading; pinning test written, owner Gradle run pending |
| M6 health and field report dropped without a selector | `SubscriptionRefreshCoordinator` and `BootstrapConsumer` read `memberHealth` and `fieldReport` from the selector group only, so a bundle without a selector lost both. `SelectorUrltestImportResult.Success` now carries them on both paths and the two callers read them there (the field report is the DGN-1795085498592630 upload target). `RipdpiBundleContractTest` `a bundle without a selector keeps member health and the field-report target` | written, owner Gradle run pending |

Checks for these fixes, logs under `/private/tmp/claude-501/-Users-po4yka-GitRep/77ff58fa-9fb9-4fb3-ac83-38ef9dcd63ce/scratchpad/runs-final-fix/`: `ktlint` on every changed Kotlin file exit 0; `python3 -m unittest scripts.tests.test_shipped_asset_secrets` 13 tests OK; `python3 scripts/ci/check_vendored_deploy_contracts.py --source <deploy>/contract` exit 0 at deployer `ce0fc650` (no contract change since `60a05950`, so no re-vendor); `just ci-gates` exit 0. No Gradle task ran, so no Kotlin test above was compiled or run.
