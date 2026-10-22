---
task_id: TRN-1792673614153980
change: device-server-migration
commit_sha: null
local: blocked
local_evidence: Final runtime-state and data suites plus static analysis and lint passed; full app suite reproduced one timeout in unchanged ProfileUtilityViewModelTest (2949 tests, one failure). Its isolated 21 tests and combined 32 migration/profile tests passed; root cause remains unresolved.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: Final received, connect-AWG and path-tested Compose renders were inspected; explicit FileProvider JSON read-grant tests passed. Native APK and exact-SHA artifact acceptance remain pending.
deployment: not_applicable
deployment_evidence: This client change does not deploy or retire a server; live operator migration remains separately authorized.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-MIGRATION-DELIVERY | TRN-1792674002992530 | `DeviceMigrationContractTest`, `DeviceMigrationCoordinatorTest`, `ImportConfirmViewModelTest`, `SubscriptionPayloadLimitsTest`, `ProxyGroupSecretAtRestTest`, `AwgProfileRepositoryRoomTest` cover strict metadata, raw-byte digest, refresh/bootstrap persistence, same-group AWG key carry, exact durable snapshots, invalid payloads and receipt confidentiality | Implemented; requirement-specific tests passed; full app gate remains blocked |
| REQ-MIGRATION-TARGET | TRN-1792674004430919 | `CandidateRelayPayloadProbeTest` exercises Reality, XHTTP and Hysteria2 candidate branches; `ActiveAwgMigrationProbeTest` exercises exact active AmneziaWG configuration and full-body probe completion | Implemented; injected-probe tests passed, real device/server reachability unverified |
| REQ-MIGRATION-FENCE | TRN-1792674004430919 | Coordinator, AWG repository and runtime tests cover delivery/profile/network/runtime changes, cancellation, key edits, replacement, session death and cleanup | Implemented; requirement-specific tests passed; full app gate remains blocked |
| REQ-MIGRATION-EXPORT | TRN-1792674005207294 | Shared received/relay/AWG HMAC vectors, strict receipt fields, explicit Compose actions, all ten locale resources, and FileProvider exact JSON/read-only grant tests | Implemented; four UI/share tests passed; all three final states rendered and inspected |

## Observed local checks

- Full affected JVM suites passed with `-Pripdpi.skipNativeBuild=true`: runtime-state
  281 tests, core/data 903 tests, core/service 2262 tests and app 2947 tests;
  no failures, errors or skipped tests at that revision. These results include
  catalog generation, recovery and card-render changes, before the final
  JSON/flow-YAML parser guard and two regression tests.
- The combined Gradle gate completed with `BUILD SUCCESSFUL` in 13m31s:
  `build-gate -- env -u CARGO_BUILD_JOBS ./gradlew :core:service:testDebugUnitTest :app:testGithubFullDebugUnitTest :core:data:runtime-state:testDebugUnitTest :core:data:testDebugUnitTest staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug -Pripdpi.skipNativeBuild=true --max-workers=4 -Pripdpi.nativeCpuBudget=4 --continue`.
  Task-specific temporary Gradle caches and build outputs were redirected to an
  external volume to avoid repeating the earlier disk exhaustion.
- After the final parser guard, all runtime-state (281) and data (903) tests,
  app/runtime-state detekt and ktlint, runtime-state lint and app full/simple
  lint passed. Service code was unchanged after its 2262-test successful run.
- Two final unfiltered app runs each reported 2949 tests, one failure, no errors
  or skips: `ProfileUtilityViewModelTest.failed checked ACK commit publishes
  neither recents nor applied configuration` timed out while its first
  measurement remained `Checking`. The unchanged class passed alone (21 tests)
  and together with `DeviceMigrationCoordinatorTest` and
  `MigrationStatusCardTest` (32 tests total). No assertion or timeout was changed.
  All migration regression cases passed; the full-suite failure cause remains
  unresolved and the local gate is blocked, not accepted.
- Final unfiltered app command:
  `build-gate -- env -u CARGO_BUILD_JOBS ./gradlew :app:testGithubFullDebugUnitTest -Pripdpi.skipNativeBuild=true --max-workers=4 -Pripdpi.nativeCpuBudget=4`.
  The diagnostic class selections above establish only diagnosis, not full-suite
  acceptance. The final parser rejects malformed RIPDPI metadata without losing
  ordinary Clash JSON or flow-YAML refresh support.
- Strict OpenSpec validation passed.
- Architecture health passed: 21 baseline indicators, no new or worsened entries.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1`
  passed. No Rust production source changed.
- Shared migration metadata, receipt, golden-vector and bundle schema resources
  are byte-identical to the companion server contract at final local verification.
- `git diff --check` and ktlint for all 44 changed Kotlin files passed.
- `just task-check` passed after reinstalling its pinned tooling: 146 tasks, 450 steps.
- All 76 existing localization regression tests passed; the 14 migration strings have matching keys and format arguments across all ten locales.

## Render inspection

Final `migration-received.png`, `migration-connect-awg.png` and
`migration-tested.png` renders under `app/build/compose-previews/renders/` have
readable status text, complete fields and buttons, visible AWG guidance and
disabled in-flight actions without clipping or overlap. These are Robolectric
native-graphics renders, not physical-device acceptance. No dedicated migration
RDS screen exists; the implementation reuses the subscription card components.
Existing screenshot baselines are unchanged.

## Remaining gates

- Diagnose and resolve the full app suite timeout, then pass the unfiltered
  app suite on the combined implementation revision.
- Complete emulator interaction and share-chooser acceptance using the
  command-scoped emulator pool lease.
- Require hosted CI, successful native build and exact-SHA APK evidence after
  authorized publication before accepting the feature as shipped.

## Resource and evidence boundaries

A fresh local native compilation failed while copying the relay native library
because the machine ran out of disk space; the fresh worktree also lacked its
Xray artifact. Only this task's disposable Rust build intermediate was removed.
An earlier combined JVM/static attempt was stopped while waiting for the shared
build gate when free space fell below 500 MiB. The earlier combined gate succeeded with
external temporary build storage; final parser follow-up checks retain the
full app suite failure described above. Native-off JVM checks establish Kotlin
behavior, not APK packaging or native transport acceptance.

Unit fixtures and injected probes establish client behavior, never actual server
reachability. No live receipt or device/server migration is claimed. No remote
telemetry or implicit upload was added. The final full app suite is blocked; hosted, device and native artifact
acceptance remain unresolved until results are observed for the implementation revision.
The task remains open and no deployment or production migration is claimed.
