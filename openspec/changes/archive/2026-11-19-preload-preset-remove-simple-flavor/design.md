## Context

`app/build.gradle.kts` declares two flavor dimensions, `distribution` (`play`, `fdroid`, `github`) and `experience` (`full`, `simple`). The `simple` source set holds `ConfigSeeder`, `SimpleHomeScreen`, and a failover package of about 2,900 lines; `app/src/full` holds the navigation host seam and the import and deep-link manifest entries. `AppStartupInitializer.initializeSubsystemsAfterRecovery` calls the optional seeder through `SimpleFlavorStartupHooks` before startup reports ready.

`ConfigSeeder` reads the gitignored asset `embedded-relay-bundle.json` (a RIPDPI sing-box bundle), validates a fixed transport mix, and writes profiles through `RelayProfileActivator`, `AwgProfileRepository`, and `AppSettingsRepository` on every launch. CI writes the asset from the secret `RIPDPI_EMBEDDED_RELAY_BUNDLE_BASE64`, or from `scripts/fixtures/embedded-relay-bundle/ci-fixture.json` on pull requests.

`BackupV1` (schema version 3) carries `groups` with their subscriptions, `profiles`, `rules`, `settings`, and, in the FULL variant, `privateData`: relay credentials, AmneziaWG profiles with secrets, WARP, and Xray records. `BackupRestoreUseCase.restore(json, selection)` has no UI dependency, stages every category before the first live write, and commits through the journaled `profileMutations.restoreBackup`. `BackupExportUseCase` always encrypts a FULL export (`BackupEncryption`: PBKDF2-HMAC-SHA256, 210,000 iterations, AES-256-GCM, header as associated data).

Findings that shaped this design:

- `simple_failover_awg_profile_id` (`app_settings.proto` field 410) has no reader outside the `simple` source set. The full app has no relay-to-AmneziaWG fallback.
- `ServiceRecoveryStartGate` in `:core:service` is an optional binding that only the `simple` source set provides.
- The UI restore path restarts the process after a restore because live observers hold the previous state.

## Goals / Non-Goals

- Goal: one application per distribution channel that can carry a complete preset.
- Goal: no new configuration format and no second restore path.
- Goal: a build without a preset needs no asset, no secret, and no fixture.
- Non-goal: a full-app relay-to-AmneziaWG fallback. It is tracked in `SVC-1795015549149698`.
- Non-goal: per-recipient preset delivery at install time. One APK carries one preset.
- Non-goal: updating an applied preset from a newer APK. The subscription in the preset owns updates.
- Non-goal: migrating data from an installed Quick Connect application.

## Decisions

- **The preset is a plaintext FULL `BackupV1` document at `app/src/main/assets/preset.json`.** It covers servers, rules, and settings in one versioned format with an existing atomic restore. Rejected: keeping the emit-bundle input and moving `ConfigSeeder` to `main`; it covers servers only and would need a second format for rules and settings. Rejected: shipping the encrypted archive with its passphrase in the APK; the passphrase would give no protection and the app would carry decryption on the startup path.
- **One class, `PresetApplier` in `app/.../seed`, replaces `SimpleFlavorSeeder`, `SimpleFlavorSessionWatcher`, `SimpleFlavorStartupHooks`, and `ConfigSeeder`.** `AppStartupInitializer` calls it directly in the slot where it calls the seeder today. It returns an outcome (applied with counts, skipped, rejected with reason) and the initializer records it; the applier holds no lifecycle.
- **Apply condition: the asset exists, onboarding is not complete, and the applied marker is absent.** The marker is one boolean in a dedicated `SharedPreferences` file, outside `AppSettings`, because the restore replaces `AppSettings`. The marker is written after the restore commits. If the process dies between the two, the next launch sees completed onboarding (when the preset sets it) or re-runs an idempotent restore of the same document. The full reset clears the marker file together with the other user stores, so a reset build returns to its preset.
- **The restore runs with all three categories selected, before readiness, with no restart.** No UI observer exists at that point. Step `AND-1795015493323024` proves this with a test that reads groups, rules, and settings through the production repositories after `initializeSubsystems`. If the test shows a stale reader, the fallback is one `ProcessPhoenix` restart before readiness, following the reset path; this fallback is not built unless the test fails.
- **An unusable preset is rejected whole and the application continues.** `ConfigSeeder` fails startup today because the Simple application is useless without servers. The full application is usable without a preset, so a rejected preset becomes a startup warning. The marker is not written on rejection.
- **Preset preparation is a Gradle task in `build-logic`, `:app:preparePreset`.** It reads the archive path from a Gradle property and the passphrase from an environment variable, decrypts with JDK `javax.crypto`, checks `schemaVersion` and `containsCredentials`, and writes the asset. Rejected: a Python script; AES-GCM needs a third-party package the repository tooling does not pin. The header layout is duplicated between `BackupEncryption` and the task; one committed encrypted fixture, decrypted by a test on each side, keeps the two in step.
- **No build-time check of the asset.** `verifyEmbeddedRelayBundle` is deleted and not replaced. `preparePreset` validates at authoring time and the application rejects safely at run time.
- **Flavor removal folds `app/src/full` into `app/src/main`.** `AppExperienceContent` becomes the private `MainNavHost` composable in `MainActivityContent.kt`; inlining it pushed its caller past the detekt function-length limit. The full manifest entries move to the main manifest. `BuildConfig.APP_EXPERIENCE` and every test branch on it are deleted.
- **Dead seams go with the flavor.** `ServiceRecoveryStartGate` and its optional-binding module are removed from `:core:service`; `RipDpiVpnService` no longer awaits it. Proto field 410 is removed and its number and name are reserved. Backup settings mappers stop writing the key; a version 1 or 2 backup that contains it still restores.
- **The Simple failover stack is deleted, not moved.** Decided by the owner on 2026-11-18. Preloaded apps switch between relay servers through selector and urltest groups only.

## Contracts and ownership

- `:app`: `PresetApplier` and its test; `AppStartupInitializer`; main manifest; `app/build.gradle.kts`.
- `:core:data`: `BackupRestoreUseCase` is called, not changed. `app_settings.proto` field 410 reserved (serialized file, single writer). `BackupV1` schema version stays 3.
- `:core:service`: `ServiceRecoveryStartGate` removal.
- `build-logic`: `preparePreset` task and its test.
- Persistence added: one `SharedPreferences` file holding one boolean. It is covered by the existing deny-all backup and data-extraction rules; it holds no identifier.
- Serialized shared files, one writer for this change: all locale resource sets and `config/i18n/translatable-keys.txt` (Simple keys removed), `app_settings.proto`, Roborazzi baselines under the Simple screenshot set (deletion only, no re-recording), detekt, lint, LoC, and architecture-health baselines (entries removed only), `app/src/main/baseline-prof.txt` (regenerated by its owning task, not edited).
- CI contract: no CI job receives a preset. A preset build carries plaintext credentials, so an operator builds it locally and it never reaches a public release artifact. The secret `RIPDPI_EMBEDDED_RELAY_BUNDLE_BASE64` has no reader after this change; the repository owner can delete it.
- Seams left unbound: `ActiveTransportProvider` (`:app`), `ExplicitUserStartPreparer`, `InitialRelayRacePolicy`, and the transport-failover apply path in `VpnServiceRuntimeCoordinator` (`:core:service`) had Simple-only bindings. Their optional bindings are empty in the full application today, so behavior does not change. `SVC-1795015549149698` decides whether a full-app fallback reuses them or they are removed.

## Risks / Trade-offs

- A restored state may not be visible without a restart → the applier test reads through production repositories; the restart fallback exists as a named option.
- The preset is readable by anyone who holds the APK, and every recipient of one APK shares its credentials and AmneziaWG key → same exposure as the embedded bundle today; documented in `docs/server-integration.md`. Per-recipient credentials need per-recipient builds or a subscription.
- Recipients lose the automatic AmneziaWG fallback → stated as a breaking change; follow-up `SVC-1795015549149698`.
- Preset authoring needs a configured device and an export, where `make emit-bundle` was scripted → accepted; an emulator can produce the export.
- Variant renames break local habits and scripts (`githubFullDebug` becomes `githubDebug`) → one commit renames every reference in CI, `justfile`, scripts, agent rules, and documents, and `just ci-gates` runs on the result.
- The decrypt logic exists twice → the shared fixture test fails when either side changes the format.
- A preset exported from a newer application than the one that packages it is rejected as unsupported → `preparePreset` checks the version against the checkout.

## Migration Plan

Forward: two commits on the job branch. The first holds the application, data, service, and build changes: `PresetApplier`, `preparePreset`, the flavor removal, and the removed seams. The second holds CI, release scripts, agent files, and documents. The edits share files (`AppStartupInitializer`, `app/build.gradle.kts`), and a contended local build gate prevented a Gradle run between smaller commits, so the gates ran on the combined tree.

Compatibility break: Quick Connect installs keep working but receive no further builds. Their data cannot move to the standard application ID. Recipients install the standard application built with a preset.

Rollback: revert the job branch commits. No stored data format changes, so an install that applied a preset keeps valid data under either version.

Rollout gates: `:app:testGithubDebugUnitTest`, `:core:data:testDebugUnitTest`, `:core:service:testDebugUnitTest`, `:app:lintGithubDebug :core:service:lintDebug`, `just ci-gates`, one emulator run of a preset build from a fresh install, and the release identity manifest for all three channels.
