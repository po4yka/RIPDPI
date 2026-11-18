# AND-1795015211660121: Preload a preset in the full app and remove the simple flavor

## Objective

Every distribution channel builds one application. A build that packages `app/src/main/assets/preset.json` restores it once on the first launch of a fresh install; a build without it is the standard application.

## Ownership

One writer, worktree `.claude/worktrees/preset-preload`, branch `feat/preset-preload`.

- `app/build.gradle.kts`, `app/src/main`, `app/src/full`, `app/src/simple`, `app/src/testGithubSimple`, `app/src/test`, `app/src/androidTest`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/ServiceRecoveryStartGate.kt`, `RipDpiVpnService.kt`, and their tests
- `core/data/model/src/main/proto/app_settings.proto`, `core/data/settings`, `core/data/src/test` (`CiRelayBundleFixtureTest`)
- `build-logic/convention`
- `.github/workflows/ci.yml`, `.github/actions/setup-android-rust/action.yml`, `scripts/ci`, `scripts/tests`, `scripts/fixtures/embedded-relay-bundle`, `justfile`, `.gitignore`
- `README*.md`, `docs/`, `.claude/rules`, `AGENTS.md`

Serialized shared-file lane: all locale resource sets and `config/i18n/translatable-keys.txt`; `app_settings.proto`; Simple Roborazzi baselines (deletion only); detekt, lint, LoC, and architecture-health baselines (entry removal only).

## Execution

- [ ] AND-1795015493323024 Add PresetApplier and call it from AppStartupInitializer before readiness, with tests for apply-once, skip, reject, and no-restart visibility #feature !high @item:AND-1795015211660121
- [ ] AND-1795015495231718 Add the preparePreset build-logic task and a shared encrypted fixture test; gitignore app/src/main/assets/preset.json #feature !high @item:AND-1795015211660121
- [ ] AND-1795015496438291 Remove the experience flavor dimension, delete the simple source sets, strings, and baselines, and fold app/src/full into main #feature !high @item:AND-1795015211660121
- [ ] AND-1795015498183385 Remove ServiceRecoveryStartGate, reserve app_settings.proto field 410, and delete the embedded-bundle fixture and its test #feature !high @item:AND-1795015211660121
- [ ] AND-1795015499321399 Rename variants and remove Simple lanes in CI, release scripts, justfile, agent rules, and documents #feature !high @item:AND-1795015211660121
- [ ] AND-1795015500558064 Run combined gates, fresh-install emulator runs with and without a preset, pr-reviewer pass, and record evidence #feature !high @item:AND-1795015211660121

## Step details

### AND-1795015493323024

- Behavior: `PresetApplier` in `app/src/main/kotlin/com/poyka/ripdpi/seed` reads `assets/preset.json` and calls `BackupRestoreUseCase.restore` with all three categories when onboarding is incomplete and the applied marker is absent. It returns an outcome that carries counts only. `AppStartupInitializer` calls it in the slot that calls the seeder today and records a rejection as a startup warning.
- Tests: one test class with an in-memory FULL fixture, reading through the production repositories. Cases: applied state is readable without a restart; second launch does not re-apply; completed onboarding is skipped; an unsupported version and an encrypted archive write nothing; no asset is a no-op; a reset clears the marker.
- Gate: `./gradlew :app:testGithubFullDebugUnitTest --tests '*PresetApplier*'`.
- Requirements: REQ-PRESET-APPLY, REQ-PRESET-ONCE, REQ-PRESET-ABSENT, REQ-PRESET-INVALID, REQ-PRESET-CONFIDENTIAL.

### AND-1795015495231718

- Behavior: `:app:preparePreset` reads the archive path from a Gradle property and the passphrase from an environment variable, decrypts with JDK crypto, rejects a wrong passphrase, a SHARE export, and an unsupported schema version, and writes the asset. `.gitignore` excludes the asset path.
- Tests: one committed encrypted fixture, decrypted by a build-logic test and by a `core/data` `BackupEncryption` test.
- Gate: `./gradlew -p build-logic test` and `./gradlew :core:data:testDebugUnitTest --tests '*BackupEncryption*'`.
- Requirements: REQ-PRESET-PREPARE, REQ-PRESET-CONFIDENTIAL.

### AND-1795015496438291

- Behavior: delete the `experience` dimension, `app/src/simple`, `app/src/testGithubSimple`, the Simple strings in all 10 locales, and the Simple Roborazzi baselines. Move `app/src/full` into `app/src/main` and inline `AppExperienceContent` into `MainActivityContent`. Delete `BuildConfig.APP_EXPERIENCE` and every test branch on it, `verifyEmbeddedRelayBundle`, `SimpleFlavorSeeder`, `SimpleFlavorSessionWatcher`, and `SimpleFlavorStartupHooks`. Regenerate `config/i18n/translatable-keys.txt` with `scripts/ci/export-strings-for-translation.sh`.
- Tests: existing manifest and import-filter tests, with their experience branches removed, assert the full behavior on every variant.
- Gate: `./gradlew :app:testGithubDebugUnitTest :app:lintGithubDebug :core:service:lintDebug` and `./gradlew :app:writeReleaseIdentityManifest`.
- Requirements: REQ-PRESET-SINGLE-EXPERIENCE, REQ-PRESET-ABSENT.

### AND-1795015498183385

- Behavior: delete `ServiceRecoveryStartGate`, its optional-binding module, and its await in `RipDpiVpnService`. Remove proto field 410 and reserve its number and name. Drop the key from the settings export mapper; a version 1 or 2 backup that contains it still restores. Delete `CiRelayBundleFixtureTest` and `scripts/fixtures/embedded-relay-bundle`.
- Tests: existing backup restore tests for versions 1 to 3; extend one with a document that contains the removed key if none does.
- Gate: `./gradlew :core:service:testDebugUnitTest :core:data:testDebugUnitTest`.
- Requirements: none added; this step removes code that the removed flavor owned.

### AND-1795015499321399

- Behavior: rename variants (`githubFullDebug` to `githubDebug`, and the same for every channel and build type) and remove Simple lanes in `.github/workflows/ci.yml`, `.github/actions/setup-android-rust/action.yml` (the embedded-bundle input becomes an optional preset input), `scripts/ci`, `scripts/tests`, `justfile`, `.claude/rules`, and `AGENTS.md`. Update `README` in all languages, `docs/server-integration.md`, `docs/testing.md`, and the architecture and contributor documents.
- Tests: the existing script tests for release preflight, identity review, tool pinning, and artifact uploads, updated to the new variant set.
- Gate: `just ci-gates` and `python3 -m pytest scripts/tests`.
- Requirements: REQ-PRESET-SINGLE-EXPERIENCE, REQ-RACE-PRODUCT-SCOPE.

### AND-1795015500558064

- Behavior: rebase on `origin/main`, run every gate in the Verification section, run a preset build and a build without a preset from a fresh install under the emulator-pool lease, obtain a `pr-reviewer` pass on the full diff, and fill `verification.md`.
- Requirements: all.

## Verification

- Local: `./gradlew :app:testGithubDebugUnitTest`, `./gradlew :core:data:testDebugUnitTest`, `./gradlew :core:service:testDebugUnitTest`, `./gradlew :app:lintGithubDebug :core:service:lintDebug`, `./gradlew -p build-logic test`, `just ci-gates`.
- CI: the full workflow on the job branch with no Simple lane and no embedded-bundle secret.
- Device: one emulator-pool run of a preset build from a fresh install, and one run of a build without a preset.
- Artifact: `./gradlew :app:writeReleaseIdentityManifest` lists three release variants and no `.simple` application ID.
