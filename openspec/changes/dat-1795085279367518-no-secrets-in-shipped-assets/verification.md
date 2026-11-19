---
task_id: DAT-1795085279367518
change: dat-1795085279367518-no-secrets-in-shipped-assets
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

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DAT-1795085279367518-001 | DAT-1795085325555940 | `python3 scripts/ci/check_shipped_asset_secrets.py` exits 0 on the tree; `test_private_key_is_rejected_with_file_and_key_but_no_value` asserts exit 1, file and key path named, value absent from stderr; `test_endpoint_metadata_and_empty_token_slot_pass` asserts exit-0 input; registered as `checkShippedAssetSecrets` in `staticAnalysis`, in `just ci-gates`, and in the CI static-gates job | passed (Python); Gradle wiring pending owner run |
| REQ-DAT-1795085279367518-002 | DAT-1795085326567906 | `python3 -m unittest scripts.tests.test_shipped_asset_secrets`: 9 tests OK, including `test_preset_password_under_main_assets_is_rejected`, flavor (`play`, `github`) and renamed-file cases; untracked files are scanned, test source sets are not | passed |
| REQ-DAT-1795085279367518-003 | DAT-1795085328294027 | `PendingBootstrapProvisioner` consumes unconsumed `BOOTSTRAP` subscriptions into the AndroidKeyStore-sealed group catalogue with typed `BootstrapProvisionOutcome`; `PendingBootstrapProvisionerTest` covers provisioned, 410 spent, and unreachable cases. `AppStartupInitializer` calls `provisionPending()` after `presetApplier.applyIfFresh()` as the failure-isolated `pending_bootstrap_provision` subsystem, launched on the application scope so the up-to-15 s bootstrap call never gates readiness; `AppStartupInitializerTest` `startup provisions a pending bootstrap token without gating readiness` asserts the delivered credential lands in the group. Written, not run (Gradle is owner-only) | pending owner run |
| REQ-DAT-1795085279367518-004 | DAT-1795085329212733 | `rotation-note.md` in this change lists key kinds, asset paths, and release versions (`v0.2.0`, `v0.2.1` Simple shards) with no secret value | passed |

## Owner checks still to run

Gradle was not run in this implementation (owner rule). Run these and record the results above:

- `./gradlew :app:testGithubDebugUnitTest --tests 'com.poyka.ripdpi.subscription.PendingBootstrapProvisionerTest' --tests 'com.poyka.ripdpi.AppStartupInitializerTest'`
- `./gradlew checkShippedAssetSecrets`
- `./gradlew staticAnalysis`

## Pending

- Conflict with AND-1795015211660121: its preset is a plaintext FULL backup with `privateData` credentials, so the guard fails `staticAnalysis` and `just ci-gates` in any checkout that holds `app/src/main/assets/preset.json`. This change's design assumed the preset carries no credentials; the preset change must narrow to a `BOOTSTRAP` token slot, or the owner must decide otherwise.
- Server side: `/bootstrap/<token>` must deliver the relay credentials (deploy D4). The client parses the existing bootstrap payload formats; no new endpoint is assumed.

## Review fixes

Findings of the independent review of `fd922df2b..HEAD`. Each Kotlin regression test below was written to fail before its fix; none was compiled or run (owner build policy: no Gradle).

| Finding | Fix and regression test | Result |
|---|---|---|
| M9 guard gaps | The guard now also rejects token, secret, uuid, auth/auth_str and obfs values and a VLESS/VMess user `id` (other ids pass), fails on an asset that is not UTF-8, and skips only exact test source-set names (`test`, `androidTest`, `testFixtures` and variants), so a flavor such as `latest` is scanned; hex integer constants in code pass like decimal ones. `python3 -m unittest scripts.tests.test_shipped_asset_secrets`: 13 tests OK, 3 of the new ones fail against the previous script (`runs-final-fix/m9-before.log`); the tree scan exits 0 | passed |
| Minor: Gradle task inputs and output | `checkShippedAssetSecrets` now excludes `.worktrees`, hidden directories, `target` and `build` from its inputs, adds `_common.py`, and writes a stamp output so it is up to date when nothing changed. Edited only; not run (Gradle is owner-only) | pending owner run |
