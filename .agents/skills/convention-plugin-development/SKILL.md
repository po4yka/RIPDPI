---
name: convention-plugin-development
description: Gradle convention plugins, shared build policy, diagnostics catalog generation, and AGP APIs.
---

## 1. Plugin Architecture

### Included Build Pattern

The project uses Gradle's **included build** (`includeBuild`) to isolate build logic
from the main build. The root `settings.gradle.kts` includes `build-logic/` which itself
is a standalone Gradle project.

```
build-logic/
  settings.gradle.kts          -- declares :convention subproject, creates "libs" catalog
  convention/
    build.gradle.kts            -- applies kotlin-dsl, declares compileOnly AGP/plugin deps
    src/main/kotlin/            -- all precompiled script plugins and helper classes
```

`build-logic/settings.gradle.kts` creates a version catalog named `libs` pointing at the
root `gradle/libs.versions.toml`. This gives convention plugins access to the same version
catalog used by application modules.

### How Plugins Are Registered

Every `*.gradle.kts` file under `src/main/kotlin/` becomes a **precompiled script plugin**
whose ID is the filename minus the `.gradle.kts` suffix. For example,
`ripdpi.android.library.gradle.kts` registers as plugin ID `ripdpi.android.library`.

No `gradlePlugin {}` block or `META-INF` descriptor is needed -- the `kotlin-dsl` plugin
handles registration automatically.

### Dependency Declaration

`build-logic/convention/build.gradle.kts` declares external Gradle plugins as
**compileOnly** dependencies so their extension types are available at compile time:

- AGP (`com.android.tools.build:gradle`)
- Kotlin Compose compiler plugin
- Hilt, KSP, detekt, ktlint, roborazzi

The `kotlinx-serialization-json` library is an **implementation** dependency because the
diagnostics catalog renderer uses it at task execution time.

## 2. Plugin Inventory

| Plugin ID | File | Purpose |
|---|---|---|
| `ripdpi.android.application` | `ripdpi.android.application.gradle.kts` | Configures AGP application module: compileSdk, minSdk, targetSdk, R8/ProGuard, artifact naming (AAB), JVM 17 |
| `ripdpi.android.library` | `ripdpi.android.library.gradle.kts` | Configures AGP library module: compileSdk, minSdk, consumer ProGuard rules, JVM 17 |
| `ripdpi.android.native` | `ripdpi.android.native.gradle.kts` | Sets NDK version, ABI filters, jniLibs packaging policy. Applied by both application and library plugins |
| `ripdpi.android.rust-native` | `ripdpi.android.rust-native.gradle.kts` | Cross-compiles Rust workspace into Android .so files via Cargo. Parallel per-ABI builds. See section 4 |
| `ripdpi.android.compose` | `ripdpi.android.compose.gradle.kts` | Enables Compose, sets stability config, optional metrics/reports on CI |
| `ripdpi.android.hilt` | `ripdpi.android.hilt.gradle.kts` | Applies Hilt + KSP, adds hilt-android and hilt-compiler dependencies |
| `ripdpi.android.serialization` | `ripdpi.android.serialization.gradle.kts` | Applies `kotlin.plugin.serialization` (one-liner) |
| `ripdpi.android.protobuf` | `ripdpi.android.protobuf.gradle.kts` | Runs protoc directly (no protobuf Gradle plugin). Detects host OS/arch, wires generated Java sources into AGP variants |
| `ripdpi.android.quality` | `ripdpi.android.quality.gradle.kts` | Aggregator: applies detekt + ktlint + lint |
| `ripdpi.android.detekt` | `ripdpi.android.detekt.gradle.kts` | Configures detekt: parallel, custom rules from `:quality:detekt-rules`, compose rules, baseline support |
| `ripdpi.android.ktlint` | `ripdpi.android.ktlint.gradle.kts` | Configures ktlint: version pin, Android mode, excludes build/generated |
| `ripdpi.android.lint` | `ripdpi.android.lint.gradle.kts` | Configures Android Lint: abortOnError, baseline, html+xml reports |
| `ripdpi.android.coverage` | `ripdpi.android.coverage.gradle.kts` | Delegates to jacoco plugin (one-liner) |
| `ripdpi.android.jacoco` | `ripdpi.android.jacoco.gradle.kts` | JaCoCo setup: excludes generated/Hilt/proto classes, registers `jacocoDebugUnitTestReport` task |
| `ripdpi.android.roborazzi` | `ripdpi.android.roborazzi.gradle.kts` | Screenshot testing: enables Android resources in unit tests, sets output dir to `src/test/screenshots` |
| `ripdpi.diagnostics.catalog` | `ripdpi.diagnostics.catalog.gradle.kts` | Registers `generateDiagnosticsCatalog` and `checkDiagnosticsCatalog` tasks. See section 3 |

### Helper Kotlin Files (not plugins)

| File | Purpose |
|---|---|
| `NativeBuildPolicy.kt` | Utility functions: `resolvedNativeAbis()`, `resolvedNativeCargoProfile()`, `resolveAndroidSdkDir()`, `resolveRustTool()`, CI/release detection |
| `DiagnosticsCatalogAssembler.kt` | Orchestrates pack loading, profile loading, validation, JSON rendering |
| `DiagnosticsCatalogDefinitions.kt` | Singleton entry point wiring the assembler with default sources |
| `DiagnosticsCatalogDomain.kt` | Domain model: `DiagnosticsCatalog`, `TargetPackDefinition`, `DiagnosticsProfileDefinition`, enums |
| `DiagnosticsCatalogDpiData.kt` | DPI-specific target pack data (IP addresses, endpoints, domains) |
| `DiagnosticsCatalogPackSource.kt` | `DefaultDiagnosticsCatalogPackSource` -- builds target pack list |
| `DiagnosticsCatalogProfileSource.kt` | `DefaultDiagnosticsCatalogProfileSource` -- builds profile list referencing packs |
| `DiagnosticsCatalogRendering.kt` | `DiagnosticsCatalogJsonRenderer` and `DiagnosticsCatalogValidator` -- serializes to JSON, validates pack refs and versions |
| `DiagnosticsCatalogSharedData.kt` | Shared constants (common domains, DNS servers) reused across packs |
| `DiagnosticsCatalogSupport.kt` | Helper functions for building domain/DNS/TCP target lists |

## 3. Diagnostics Catalog Generator

The catalog defines **target packs** (groups of network endpoints) and **profiles**
(scan configurations referencing packs). It is checked into the repo as a JSON asset at
`core/diagnostics/src/main/assets/diagnostics/default_profiles.json`.

### Data Flow

1. `DiagnosticsCatalogPackSource` builds `List<TargetPackDefinition>` from typed Kotlin data
2. `DiagnosticsCatalogProfileSource` builds `List<DiagnosticsProfileDefinition>`, resolving pack refs
3. `DiagnosticsCatalogValidator` checks for duplicate IDs and verifies every `packRef` points to a real pack with matching version
4. `DiagnosticsCatalogJsonRenderer` serializes to pretty-printed JSON using kotlinx.serialization
5. `DiagnosticsCatalogAssembler` orchestrates steps 1-4

### Tasks

- `generateDiagnosticsCatalog` -- writes the JSON to the asset path. Run manually after changing data.
- `checkDiagnosticsCatalog` -- runs during `check`, fails if the committed file differs from what would be generated. This prevents stale catalogs from shipping.

Both tasks use `DiagnosticsCatalogGeneratedAt` (a date constant in `DiagnosticsCatalogDomain.kt`)
as an `@Input` to force re-execution when the generation date changes.

### Adding a New Target Pack or Profile

1. Add data to `DiagnosticsCatalogDpiData.kt` (packs) or `DiagnosticsCatalogProfileSource.kt` (profiles)
2. Run `:core:diagnostics:generateDiagnosticsCatalog`
3. Commit the updated JSON asset alongside the Kotlin changes

## 4. Rust-Native Plugin

`ripdpi.android.rust-native.gradle.kts` is the most complex plugin. It cross-compiles
Rust crates into Android `.so` libraries.

### Cargo Invocation

The `BuildRustNativeLibsTask` is a `@CacheableTask` that:

1. Validates all requested ABIs have installed Rust targets (`rustup target list --installed`)
2. Resolves the NDK toolchain bin directory for the host platform (linux-x86_64, darwin-arm64, etc.)
3. Builds all ABIs **in parallel** using a thread pool capped to available CPUs
4. For each ABI, sets environment variables: `CC_<target>`, `AR_<target>`, `CARGO_TARGET_<target>_LINKER`, `CARGO_TARGET_DIR`
5. Runs `cargo build --manifest-path ... -p <package> --locked --target <triple> --profile <profile> --jobs <n>`
6. Copies output `.so` files to `build/generated/jniLibs/<abi>/`

### ABI Mapping

| Android ABI | Rust Target Triple | Clang Target Prefix |
|---|---|---|
| `armeabi-v7a` | `armv7-linux-androideabi` | `armv7a-linux-androideabi` |
| `arm64-v8a` | `aarch64-linux-android` | `aarch64-linux-android` |
| `x86` | `i686-linux-android` | `i686-linux-android` |
| `x86_64` | `x86_64-linux-android` | `x86_64-linux-android` |

### Profile Selection (NativeBuildPolicy.kt)

- **CI or release-like builds**: uses `ripdpi.nativeCargoProfile` (default: `android-jni`)
- **Local dev builds**: uses `ripdpi.localNativeCargoProfileDefault` (default: `android-jni-dev`) for faster iteration
- **ABI narrowing**: local builds default to `arm64-v8a` only (`ripdpi.localNativeAbisDefault`), CI builds compile all four ABIs

The detection logic is in `resolvedNativeCargoProfile()` and `resolvedNativeAbis()` in
`NativeBuildPolicy.kt`. A build is considered "release-like" if any task name contains
"release", "bundle", or "publish".

### Task Wiring

The plugin hooks `buildRustNativeLibs` into AGP's JNI packaging pipeline by making
`merge*JniLibFolders`, `copy*JniLibsProjectOnly`, `merge*NativeLibs`, and `preBuild`
depend on it. This ensures Rust source changes trigger repackaging even when Android
sources are unchanged.

### Artifact Specs

Artifacts are declared as pipe-delimited strings: `"<cargo-package>|<cargo-output>|<jniLibs-name>"`.
Current artifacts:
- `ripdpi-android|libripdpi_android.so|libripdpi.so`
- `ripdpi-tunnel-android|libripdpi_tunnel_android.so|libripdpi-tunnel.so`

### Cache Invalidation

The task's `@InputFiles` include:
- `Cargo.toml`, `Cargo.lock`, `rust-toolchain.toml`
- The entire `.cargo/` directory
- All source directories for crates listed in `rustNativePackageDirs` (excluding `target/`)

Keep `rustNativePackageDirs` aligned with the actual dependency closure of the JNI crates.
If a new crate is added that `ripdpi-android` or `ripdpi-tunnel-android` depends on,
add its directory name to the list or Gradle will skip the Rust rebuild when that crate changes.

## 5. Adding a New Convention Plugin

### Step-by-Step

1. Create `build-logic/convention/src/main/kotlin/ripdpi.<category>.<name>.gradle.kts`
2. If the plugin applies an external Gradle plugin, add its artifact as a `compileOnly`
   dependency in `build-logic/convention/build.gradle.kts` using the `libs.plugins.<id>.map { ... }` pattern
3. Write the plugin body. Access shared properties via `providers.gradleProperty("ripdpi.<property>")`
4. Apply the plugin in consuming modules: `id("ripdpi.<category>.<name>")`
5. Verify with `./gradlew :app:help` (or whichever module applies it) to confirm resolution

### Naming Convention

- `ripdpi.android.*` -- plugins that configure Android modules (AGP extensions, Kotlin, quality tools)
- `ripdpi.diagnostics.*` -- plugins for the diagnostics subsystem
- Use dots as separators. The filename IS the plugin ID.

### Helper Classes

Place reusable Kotlin code in plain `.kt` files alongside the plugins (not inside a
package). These files are compiled into the same classpath and are directly accessible
from any precompiled script plugin in the module.

## 6. Common Pitfalls

### Configuration Cache Compatibility

The project enables `org.gradle.configuration-cache=true`. Every plugin must be
configuration-cache safe:
- Do not capture `Project` references in task actions. Use `@Input`/`@InputFile` properties.
- Use `providers.gradleProperty()` instead of reading properties eagerly at configuration time.
- Task classes that call `ExecOperations` must inject it via `@Inject constructor`.

### AGP API Compatibility

- Use `extensions.configure<ApplicationExtension>` (the DSL type), not the internal
  `BaseAppModuleExtension`. Internal types break across AGP upgrades.
- For variant-aware wiring, use `ApplicationAndroidComponentsExtension` or
  `LibraryAndroidComponentsExtension` with `onVariants {}`.
- The protobuf plugin uses `variant.sources.java?.addGeneratedSourceDirectory()` to wire
  generated sources -- this is the modern AGP variant API approach.

### Included Build Quirks

- Version catalog access in `build.gradle.kts` uses `libs.versions.<name>` and
  `libs.plugins.<name>`. Inside precompiled script plugins, use
  `the<VersionCatalogsExtension>().named("libs")` or `versionCatalogs.named("libs")`.
- Changes to `build-logic/` sources require a Gradle sync in the IDE. Gradle does not
  auto-detect included build source changes in all cases.
- The `kotlin-dsl` plugin implicitly applies `java-gradle-plugin` and
  `kotlin("jvm")`. Do not apply them again.

### Baseline Policy

Per project rules (CLAUDE.md): never extend detekt baselines, lint baselines, or LoC
baselines to suppress new violations. Fix the underlying issue. Baselines exist only
for legacy debt.

### Rust-Native Cache Misses

If the Rust build runs unexpectedly:
1. Check if a crate directory is missing from `rustNativePackageDirs`
2. Check if `Cargo.lock` changed (dependency update triggers rebuild)
3. Verify the profile selection logic -- local dev should use `android-jni-dev`

### Protobuf Plugin

The project deliberately avoids the `protobuf-gradle-plugin` and instead uses a custom
`GenerateProtoLiteSourcesTask` that downloads protoc as a detached configuration and
runs it directly. This avoids version conflicts and configuration-cache issues with the
official plugin.
