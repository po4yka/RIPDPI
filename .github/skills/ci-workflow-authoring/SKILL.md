---
name: ci-workflow-authoring
description: Use when writing or modifying GitHub Actions workflows, adding CI jobs, configuring caching, fixing CI failures, or understanding the CI pipeline architecture. Triggers on: CI workflow, GitHub Actions, workflow file, CI pipeline, nightly build, caching strategy, workflow dispatch, CI failing.
---

# CI Workflow Authoring

Three GitHub Actions workflows in `.github/workflows/`:

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `ci.yml` | Push/PR to main, nightly schedule, manual | Build, test, static analysis, E2E, soak |
| `release.yml` | Tag `v*` or manual dispatch | Signed release build + GitHub Release |
| `mutation-testing.yml` | Weekly Monday 06:00 UTC, manual | Rust mutation testing via cargo-mutants |

## CI Architecture (`ci.yml`)

### Job Dependency Graph

```
build (always)
  +-> static-analysis
  +-> release-verification (minified release APK)
  +-> coverage (Kotlin + Rust thresholds)
  +-> rust-network-e2e
  +-> cli-packet-smoke
  +-> rust-turmoil (deterministic network)
  +-> rust-loom (concurrency)

Nightly-only (schedule or manual):
  +-> rust-native-soak (full/smoke profiles)
  +-> nightly-rust-coverage
  +-> android-network-e2e (emulator)
  +-> linux-tun-e2e
  +-> linux-tun-soak
```

### Concurrency

```yaml
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
```

Groups by ref -- new pushes to the same branch cancel in-progress runs.

## Environment Setup Pattern

Most jobs need this setup. Copy from existing jobs:

```yaml
steps:
  - uses: actions/checkout@v4

  # Java (for Gradle)
  - uses: actions/setup-java@v4
    with:
      distribution: temurin
      java-version: 17

  # Rust (for native code)
  - uses: dtolnay/rust-toolchain@master
    with:
      toolchain: "1.94.0"  # Match rust-toolchain.toml
      components: rustfmt, clippy
      targets: aarch64-linux-android,armv7-linux-androideabi,i686-linux-android,x86_64-linux-android

  # NDK (read version from gradle.properties)
  - name: Read NDK version
    id: ndk
    run: echo "version=$(grep 'androidNdkVersion' gradle.properties | cut -d= -f2)" >> "$GITHUB_OUTPUT"
  - uses: nttld/setup-ndk@v1
    with:
      ndk-version: ${{ steps.ndk.version }}
```

**Key:** NDK version is read from `gradle.properties` (single source of truth), not hardcoded in the workflow.

## Caching Strategy

| Cache | Implementation | Key |
|-------|---------------|-----|
| Gradle | `actions/cache` on `~/.gradle/caches` | `gradle-${{ hashFiles('**/*.gradle.kts', 'gradle/libs.versions.toml') }}` |
| Rust (sccache) | `mozilla-actions/sccache-action` | Automatic by content hash |
| NDK | Cached by `setup-ndk` action | NDK version string |
| cargo-nextest | `actions/cache` on `~/.cargo/bin/cargo-nextest` | Tool version |

## Conditional Jobs

### Nightly-Only Jobs

```yaml
jobs:
  rust-native-soak:
    if: github.event_name == 'schedule' || github.event.inputs.soak_profile != ''
    # ...
```

### Manual Dispatch Inputs

```yaml
on:
  workflow_dispatch:
    inputs:
      soak_profile:
        description: "Soak profile (full, smoke)"
        type: choice
        options: ["", "full", "smoke"]
```

## Adding a New CI Job

Checklist:

1. **Define the job** with `needs: [build]` if it depends on build artifacts
2. **Set up environment** -- copy Java/Rust/NDK setup steps from existing job
3. **Configure caching** -- add Gradle and/or Rust cache steps
4. **Set timeout** -- `timeout-minutes: 30` (adjust based on expected duration)
5. **Add concurrency group** if the job should cancel on new pushes
6. **Upload artifacts** for test results, logs, or reports:
   ```yaml
   - uses: actions/upload-artifact@v4
     if: always()
     with:
       name: my-results
       path: path/to/results/
       retention-days: 14
   ```
7. **Gate on schedule** if the job should only run nightly:
   ```yaml
   if: github.event_name == 'schedule'
   ```

### Job Template

```yaml
my-new-job:
  needs: [build]
  runs-on: ubuntu-latest
  timeout-minutes: 30
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with: { distribution: temurin, java-version: 17 }
    # Add Rust/NDK setup if needed
    - name: Run tests
      run: ./gradlew :module:testDebugUnitTest
    - uses: actions/upload-artifact@v4
      if: always()
      with:
        name: test-results
        path: module/build/reports/
        retention-days: 14
```

## Release Workflow (`release.yml`)

Triggered by `v*` tags. Key steps:
1. Decode base64 keystore from secrets
2. `./gradlew bundleRelease assembleRelease` with signing env vars
3. Upload AAB, APK, R8 mapping, native symbols (90-day retention)
4. Create GitHub Release with auto-generated notes

See `release-signing` skill for signing config details.

## Mutation Testing (`mutation-testing.yml`)

- Runs `cargo-mutants` on the Rust workspace
- Manual inputs: package filter, diff-only mode
- Artifacts: `target/mutants-output/` (14-day retention)

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Hardcoding NDK version in workflow | Read from `gradle.properties` using grep + `GITHUB_OUTPUT` |
| Missing NDK setup for native jobs | Any job that builds native code needs the NDK setup step |
| Wrong concurrency group | Use `ci-${{ github.ref }}` to scope per-branch |
| No `if: always()` on artifact upload | Without it, artifacts aren't uploaded when tests fail (when you need them most) |
| Missing `timeout-minutes` | Jobs without timeout can hang indefinitely and consume CI minutes |
| Caching too broadly | Narrow cache keys to relevant files (e.g., `*.gradle.kts` not `**/*`) |
| Not testing workflow changes locally | Use `act` tool (see `local-ci-act` skill) before pushing |

## See Also

- `.github/skills/local-ci-act/SKILL.md` -- Running workflows locally with `act`
- `.github/skills/release-signing/SKILL.md` -- Release signing and R8 configuration
- `.github/skills/dependency-update/SKILL.md` -- Version management that affects CI setup
