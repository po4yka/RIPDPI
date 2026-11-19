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

`local_evidence` stays null until the owner runs the Gradle gates below. Every new and changed assertion except the vendored-contract drift check is a Kotlin unit test, which no session compiled or ran (owner build policy).

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

## Gradle gates left for the owner

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
