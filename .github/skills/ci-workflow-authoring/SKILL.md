---
name: ci-workflow-authoring
description: GitHub Actions authoring for workflows, CI jobs, caches, artifacts, and failure triage.
---

# CI Workflow Authoring

Four GitHub Actions workflows live in `.github/workflows/`:

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `ci.yml` | Push/PR to main, daily schedule, manual dispatch | Main validation pipeline: build, static analysis, release verification, coverage, Rust lanes, benchmarks, Android E2E, soak/load, Linux TUN |
| `codeql.yml` | Push/PR to main, weekly schedule | CodeQL analysis for GitHub Actions; Kotlin analysis is intentionally disabled for now |
| `release.yml` | Tag `v*` or manual dispatch | Signed release build, artifact upload, optional GitHub Release |
| `mutation-testing.yml` | Weekly Monday 06:00 UTC, manual dispatch | Rust mutation testing via `cargo-mutants` |

## CI Architecture (`ci.yml`)

### Non-scheduled lanes

```text
build
  -> static-analysis
  -> release-verification
  -> coverage
  -> rust-network-e2e
  -> cli-packet-smoke
  -> rust-turmoil
  -> rust-criterion-bench
  -> android-macrobenchmark
  -> rust-loom
```

### Scheduled / manual lanes

```text
rust-native-soak
rust-native-load
nightly-rust-coverage
android-network-e2e
linux-tun-e2e
linux-tun-soak
```

`android-network-e2e` also runs on regular CI, but Maestro/Appium smoke add-ons are gated behind `workflow_dispatch` inputs.

### Concurrency

```yaml
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
```

This is per-ref cancellation, not per-workflow-name cancellation.

## Environment Setup Pattern

Most Android/native jobs follow this skeleton:

```yaml
steps:
  - uses: actions/checkout@v6

  - uses: actions/setup-java@v5
    with:
      distribution: temurin
      java-version: 17

  - uses: dtolnay/rust-toolchain@master
    with:
      toolchain: "1.94.0"
      components: rustfmt, clippy

  - uses: android-actions/setup-android@v4

  - uses: Swatinem/rust-cache@v2
    with:
      workspaces: native/rust -> target
      cache-on-failure: true

  - name: Read native toolchain policy
    id: native-toolchain
    run: |
      echo "ndk=$(grep '^ripdpi.nativeNdkVersion=' gradle.properties | cut -d= -f2-)" >> "$GITHUB_OUTPUT"

  - name: Install Rust Android targets
    run: rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android

  - name: Install NDK
    run: sdkmanager --install "ndk;${{ steps.native-toolchain.outputs.ndk }}"

  - uses: gradle/actions/setup-gradle@v6
```

For native-heavy Android jobs, `mozilla-actions/sccache-action@v0.0.9` is added before Gradle runs.

## Caching Strategy

| Cache | Implementation | Notes |
|-------|---------------|-------|
| Gradle | `gradle/actions/setup-gradle@v6` | Preferred over hand-rolled cache blocks |
| Rust workspace | `Swatinem/rust-cache@v2` | Caches `native/rust -> target` |
| Rust compiler cache | `mozilla-actions/sccache-action@v0.0.9` | Used on native-heavy jobs |
| Benchmark baselines | `actions/cache/restore` / `save` | Used by criterion baselines on PRs and main |
| Tool installs | `taiki-e/install-action@v2` | Used for `cargo-nextest`, `cargo-llvm-cov`, `cargo-bloat` |

## Manual Dispatch Inputs

`ci.yml` currently exposes:

```yaml
workflow_dispatch:
  inputs:
    soak_profile: smoke|full
    run_maestro_smoke: true|false
    run_appium_smoke: true|false
```

If you add a new manual-only lane, wire its input into both the job `if:` condition and the step logic that consumes it.

## Adding or Modifying a CI Job

Checklist:

1. Decide whether the job belongs in `ci.yml`, `release.yml`, `mutation-testing.yml`, or `codeql.yml`.
2. Copy environment setup from the nearest existing job instead of inventing a new setup pattern.
3. Read the NDK version from `gradle.properties`; never hardcode it in YAML.
4. Add `timeout-minutes` unless the job is trivially short.
5. Use `if: always()` on artifact upload steps.
6. Keep artifact names stable when downstream debugging depends on them.
7. If the job is nightly-only, gate it explicitly on `schedule` or `workflow_dispatch`.
8. If the job uses emulator infrastructure, prefer the existing `reactivecircus/android-emulator-runner@v2` pattern.

### Job Template

```yaml
my-new-job:
  needs: [build]
  runs-on: ubuntu-latest
  timeout-minutes: 30
  steps:
    - uses: actions/checkout@v6
    - uses: actions/setup-java@v5
      with:
        distribution: temurin
        java-version: 17
    - name: Run tests
      run: ./gradlew :module:testDebugUnitTest
    - uses: actions/upload-artifact@v7
      if: always()
      with:
        name: my-results
        path: module/build/reports/
        retention-days: 7
```

## CodeQL Workflow (`codeql.yml`)

- Current scope is only `language: actions`.
- Kotlin/Java analysis is commented out until `github/codeql-action` supports the repo's Kotlin 2.3.20 toolchain.
- If re-enabling Kotlin analysis, restore explicit Android/JDK build steps rather than assuming the default CodeQL autobuild is enough.

## Release Workflow (`release.yml`)

Triggered by `v*` tags or manual dispatch.

Key behaviors:

1. Decode the base64 keystore secret.
2. Run `./gradlew bundleRelease -Pripdpi.enableAbiSplits=false` then `./gradlew assembleRelease`.
3. Upload AAB, APK, mapping files, compose mapping, and native symbols.
4. Optionally create a GitHub Release.

See `release-signing` for signing and R8 details.

## Mutation Testing Workflow (`mutation-testing.yml`)

- Runs `cargo-mutants` against the Rust workspace.
- Exposes manual filters such as package selection and diff-only mode.
- Produces `target/mutants-output/` artifacts.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Hardcoding the NDK version | Read `ripdpi.nativeNdkVersion` from `gradle.properties` |
| Using outdated action versions from old workflow snippets | Match the versions already used in this repo (`checkout@v6`, `setup-java@v5`, `setup-gradle@v6`, etc.) |
| Forgetting schedule/manual gating on soak or load jobs | Mirror the existing `schedule || workflow_dispatch` pattern |
| Uploading artifacts only on success | Use `if: always()` so failure artifacts are preserved |
| Treating CodeQL as if Kotlin were enabled | The current workflow analyzes only GitHub Actions files |
| Testing Android emulator jobs with the wrong assumptions | Reuse the existing emulator-runner configuration and manual smoke gating |

## See Also

- `.github/skills/local-ci-act/SKILL.md` -- Local workflow execution with `act`
- `.github/skills/release-signing/SKILL.md` -- Release signing and R8 details
- `.github/skills/dependency-update/SKILL.md` -- Version changes that affect workflow setup
