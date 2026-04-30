---
name: dependency-update
description: Gradle and Rust dependency updates, version catalogs, AGP/Kotlin/NDK bumps, and Renovate.
---

# Dependency Update

Two dependency ecosystems (Gradle + Cargo) with Renovate automating PR creation.

## Gradle Version Catalog

**File:** `gradle/libs.versions.toml`

Structure:
```toml
[versions]
agp = "9.1.0"              # <-- update versions HERE
kotlin = "2.3.20"

[libraries]
androidx-core = { group = "androidx.core", name = "core-ktx", version.ref = "androidxCore" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }

[bundles]
lifecycle = ["lifecycle-runtime", "lifecycle-viewmodel"]
```

**Rule:** Always update the version in the `[versions]` section. Never inline a version in `[libraries]` or `[plugins]`.

### Key Version Groups

| Group | Versions to Update Together |
|-------|-----------------------------|
| Kotlin | `kotlin`, `ksp`, `kotlinComposeCompiler` |
| Compose | `composeBom` (single BOM controls all Compose libs) |
| AndroidX | Individually, but Renovate groups them |
| AGP | `agp` (may require Gradle wrapper update) |
| Testing | `junit`, `robolectric`, `roborazzi` independently |

## Rust Dependencies

**File:** `native/rust/Cargo.toml` (workspace root)

Dependencies declared in `[workspace.dependencies]` are inherited by crates via `{ workspace = true }`:

```toml
[workspace.dependencies]
tokio = { version = "1.45", features = ["full"] }
serde = { version = "1", features = ["derive"] }
```

Update a specific crate:
```bash
cd native/rust
cargo update -p tokio
```

Update all:
```bash
cd native/rust
cargo update
```

## Renovate Configuration

**File:** `renovate.json`

Grouping rules:
- **AndroidX** packages grouped into a single PR
- **Kotlin + Compose Compiler** grouped together
- **Static analysis** (detekt, ktlint) grouped together
- All dependency PRs labeled `dependencies`
- Git submodules enabled

## Update Workflows

### Single Gradle Dependency

```bash
# 1. Edit version in libs.versions.toml
# 2. Verify
./gradlew assembleDebug && ./gradlew testDebugUnitTest
```

### AGP / Gradle Upgrade

```bash
# 1. Update agp version in libs.versions.toml
# 2. May need Gradle wrapper update:
./gradlew wrapper --gradle-version=X.Y.Z
# 3. Full verification:
./gradlew assembleDebug testDebugUnitTest staticAnalysis
```

### Kotlin Version Update

Update all three together in `libs.versions.toml`:
- `kotlin` (Kotlin compiler)
- `ksp` (must match Kotlin major.minor)
- `kotlinComposeCompiler` (Compose compiler plugin)

```bash
./gradlew assembleDebug testDebugUnitTest staticAnalysis
```

### Rust Dependency Update

```bash
cd native/rust
cargo update -p <crate-name>
cargo test
cargo clippy --workspace --all-targets -- -D warnings
# Rebuild Android libs to verify cross-compilation:
cd ../.. && ./gradlew :core:engine:buildRustNativeLibs
```

### NDK Version Update

NDK changes affect **both** ecosystems:

1. Update `ripdpi.nativeNdkVersion` in `gradle.properties`
2. Verify Rust cross-compilation targets still work:
   ```bash
   ./gradlew :core:engine:buildRustNativeLibs
   ```
3. Full test suite:
   ```bash
   ./gradlew assembleDebug testDebugUnitTest
   cd native/rust && cargo test
   ```

### Rust Toolchain Update

1. Update `channel` in `native/rust/rust-toolchain.toml`
2. Verify CI workflow uses the same version (reads from `rust-toolchain.toml`)
3. Run: `cd native/rust && cargo test && cargo clippy --workspace`

## Cross-Ecosystem Dependencies

| Change | Affects |
|--------|---------|
| NDK version | `gradle.properties` + Rust cross-compilation targets |
| Rust toolchain | `rust-toolchain.toml` + CI workflow setup |
| JNI API changes | Kotlin bindings in `core/engine` + Rust crates `ripdpi-android`, `ripdpi-tunnel-android` |

## Verification Checklist

After any update, run in order:

```bash
./gradlew assembleDebug                          # Kotlin compilation
./gradlew testDebugUnitTest                      # Unit tests
./gradlew staticAnalysis                         # detekt + ktlint + lint
cd native/rust && cargo test                     # Rust tests
cd ../.. && ./gradlew :core:engine:buildRustNativeLibs  # Cross-compilation
```

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Inline version in `[libraries]` instead of `[versions]` | Always use `version.ref` pointing to `[versions]` entry |
| Updating Kotlin without KSP | KSP version must match Kotlin major.minor. Update together. |
| Forgetting `Cargo.lock` | `Cargo.lock` is committed for reproducible builds. Run `cargo update` and commit the lock file. |
| NDK update without rebuilding native libs | NDK changes require `./gradlew :core:engine:buildRustNativeLibs` to verify. |
| Updating Compose libs individually | Use `composeBom` version -- single BOM controls all Compose library versions. |
| Rust toolchain update without CI check | CI reads `rust-toolchain.toml` -- verify the version exists and targets are available. |

## See Also

- `.github/skills/gradle-build-system/SKILL.md` -- Convention plugins and dependency management patterns
- `.github/skills/ci-workflow-authoring/SKILL.md` -- CI environment setup that depends on these versions
