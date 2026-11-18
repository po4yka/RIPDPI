# Change: Preload a preset in the full app and remove the simple flavor

Task ID: `AND-1795015211660121`

## Why

The `simple` experience flavor exists to hand a field tester an app that already contains servers. To do that it carries a second flavor dimension, a separate application ID, its own home screen, and its own failover stack: about 4,000 lines of production code and 8,000 lines of tests, three more release variants, and separate unit, screenshot, and device lanes in CI. The only part a recipient needs is the preloaded configuration, and the seeder that supplies it covers servers only, not routing rules or settings.

The full app already has a format and a restore path that carry servers, routing rules, and settings together: the FULL backup document and `BackupRestoreUseCase`. Shipping that document as an optional asset gives a recipient the complete app with a complete setup, and the flavor is no longer needed.

## What Changes

- A build MAY package a preset: a FULL backup document at a fixed asset path. On the first launch of a fresh install the app restores the preset's groups, profiles, credentials, routing rules, and settings before the first screen appears.
- The preset is applied once. Later launches and app updates never re-apply it and never overwrite user edits. Server changes reach the user through the subscription stored in the preset.
- A build without a preset behaves exactly as the full app does today.
- An operator converts an encrypted FULL export into the preset asset with one repository command.
- **BREAKING**: the `experience` flavor dimension is removed. Variants are named by distribution only (for example `githubDebug`, `playRelease`), so every task, script, and CI lane that names a `Full` or `Simple` variant changes.
- **BREAKING**: the "RIPDPI Quick Connect" application (`.simple` application ID) is discontinued. Installed copies do not migrate; a recipient installs the standard application.
- **BREAKING**: the two-action Simple home screen is removed. A preset selects the guided persona through its settings.
- **BREAKING**: the automatic fallback from relay servers to the AmneziaWG device VPN, the VLESS runtime monitor, and the per-launch reconcile of bundled profiles are removed with the flavor. The full app keeps selector and urltest failover between relay servers. A full-app AmneziaWG fallback is tracked separately in `SVC-1795015549149698`.
- **BREAKING**: the compiled-in `embedded-relay-bundle.json` input and its CI secret are removed. A RIPDPI bundle still imports through a subscription URL.

## Capabilities

### New Capabilities

- `first-launch-preset`: packaging, one-time application, absence, failure handling, and confidentiality of a preset, and the single application experience per distribution channel.

### Modified Capabilities

- `initial-relay-race-safety`: `REQ-RACE-SCOPE` no longer names the Simple single-profile preflight.

## Impact

- `:app`: `app/build.gradle.kts` flavor dimensions; `app/src/simple`, `app/src/testGithubSimple`, and `app/src/full` source sets; `AppStartupInitializer`; the `seed` package; Simple string resources in all 10 locales; Simple Roborazzi baselines.
- `:core:service`: the optional `ServiceRecoveryStartGate` seam, which only the Simple flavor binds.
- `:core:data`: `BackupRestoreUseCase` gains a startup caller; `app_settings.proto` field 410 (`simple_failover_awg_profile_id`) is reserved; `CiRelayBundleFixtureTest` and its fixture are removed.
- `build-logic`: a preset preparation task; removal of `verifyEmbeddedRelayBundle`.
- CI and release: `.github/workflows/ci.yml`, `.github/actions/setup-android-rust`, release preflight and identity scripts and their tests, `justfile`, and the secret `RIPDPI_EMBEDDED_RELAY_BUNDLE_BASE64`, which a preset secret replaces.
- Documentation: `README*.md`, `docs/server-integration.md`, `docs/testing.md`, architecture and contributor documents that name the Simple flavor.
- Serialized shared files: all locale resource sets, `app_settings.proto`, golden fixtures (deletion only), baseline and architecture-health files.
