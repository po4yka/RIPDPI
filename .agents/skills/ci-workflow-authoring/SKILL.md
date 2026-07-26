---
name: ci-workflow-authoring
description: GitHub Actions authoring for workflows, CI jobs, caches, artifacts, and failure triage.
---

# CI Workflow Authoring

Workflow count, jobs, dispatch inputs, action versions, and concurrency rules
are source-controlled in `.github/workflows/`. Inspect the current manifests
and copy the nearest existing job; do not maintain a second hand-written
inventory here.

## CI architecture (`ci.yml`)

Derive the current job graph, triggers, dispatch inputs, and `concurrency:`
policy directly from `.github/workflows/ci.yml`; these change too often for a
second inventory here.

## Environment Setup Pattern

Most Android/native jobs reuse `.github/actions/setup-android-rust`. Copy that
composite action or the nearest current job, including every exact action SHA;
never substitute a floating tag from an example.

## Caching Strategy

| Cache | Implementation | Notes |
|-------|---------------|-------|
| Gradle | Exact pinned `gradle/actions/setup-gradle` SHA from the nearest job | Preferred over hand-rolled cache blocks |
| Rust workspace | Exact pinned `Swatinem/rust-cache` SHA from the nearest job | Caches `native/rust -> target` |
| Rust compiler cache | Exact pinned sccache action SHA from the nearest job | Used on native-heavy jobs |
| Benchmark baselines | `actions/cache/restore` / `save` | Used by criterion baselines on PRs and main |
| Tool installs | `taiki-e/install-action@v2` | Used for `cargo-nextest`, `cargo-llvm-cov`, `cargo-bloat` |

## Manual Dispatch Inputs

Derive the current inputs from the `workflow_dispatch.inputs` mapping in
`ci.yml`; it changes as optional lanes evolve. Typical inputs include:

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
8. If the job runs `:app` or `:baselineprofile` instrumented tests, use the Gradle Managed Device pattern: a `<device>...AndroidTest` task plus the shared device registry in `build-logic/convention/src/main/kotlin/RipDpiManagedDevices.kt` (see the `android-instrumented-tests` and `android-macrobenchmark` jobs). Specialized emulator lanes (journeys, relay-smoke, network-e2e) still use the `scripts/ci/start-android-emulator.sh` harness.

### Job template

```yaml
my-new-job:
  needs: [build]
  runs-on: ubuntu-latest
  timeout-minutes: 30
  steps:
    - uses: actions/checkout@<copy-exact-pinned-sha-from-nearest-job>
    - uses: ./.github/actions/setup-android-rust
    - name: Run tests
      run: ./gradlew :module:testDebugUnitTest
    - uses: actions/upload-artifact@<copy-exact-pinned-sha-from-nearest-job>
      if: always()
      with:
        name: my-results
        path: module/build/reports/
        retention-days: 7
```

## CodeQL Workflow (`codeql.yml`)

- Current scope is only `language: actions`.
- Kotlin/Java analysis remains disabled; re-check the current workflow comment and active Kotlin version before changing that decision.
- If re-enabling Kotlin analysis, restore explicit Android/JDK build steps rather than assuming the default CodeQL autobuild is enough.

## Release Workflow (`release.yml`)

Triggered by `v*` tags or manual dispatch.

Key behaviors:

1. Decode the base64 keystore secret.
2. Run the flavor-qualified Play/Fdroid/Github release tasks defined in `release.yml`.
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
| Using outdated action versions from old workflow snippets | Copy the exact pinned SHA and version comment from the nearest current workflow job |
| Forgetting schedule/manual gating on soak or load jobs | Mirror the existing `schedule || workflow_dispatch` pattern |
| Uploading artifacts only on success | Use `if: always()` so failure artifacts are preserved |
| Treating CodeQL as if Kotlin were enabled | The current workflow analyzes only GitHub Actions files |
| Hand-rolling emulator setup for instrumented tests | Use the Gradle Managed Device pattern (`RipDpiManagedDevices.kt` + a `<device>...AndroidTest` task); reserve `scripts/ci/start-android-emulator.sh` for the journeys/relay/network-e2e lanes |

## See Also

- `.github/skills/local-ci-act/SKILL.md` -- Local workflow execution with `act`
- `.github/skills/release-signing/SKILL.md` -- Release signing and R8 details
- `.github/skills/dependency-update/SKILL.md` -- Version changes that affect workflow setup
