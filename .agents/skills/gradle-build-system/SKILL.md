---
name: gradle-build-system
description: Gradle dependencies, modules, convention plugins, and Android build failure triage.
---

# Gradle Build System

## Overview

RIPDPI uses the Gradle version pinned by `gradle/wrapper/gradle-wrapper.properties`, convention plugins in `build-logic/convention/`, and the version catalog at `gradle/libs.versions.toml`. Read those source files before reporting versions; all module build configuration flows through convention plugins.

## Convention Plugins

| Plugin | Purpose |
|--------|---------|
| `ripdpi.android.application` | App module: compileSdk, minSdk, targetSdk, JDK 17 |
| `ripdpi.android.library` | Library modules: compileSdk, minSdk, JDK 17 |
| `ripdpi.android.compose` | Compose compiler configuration |
| `ripdpi.android.native` | NDK version, ABI filters, legacy packaging |
| `ripdpi.android.quality` | Shared detekt + ktlint + Android lint verification wiring |
| `ripdpi.android.coverage` | Shared JaCoCo coverage wiring |
| `ripdpi.android.protobuf` | Protobuf code generation setup |

Plugin sources: `build-logic/convention/src/main/kotlin/ripdpi.android.*.gradle.kts`

## Adding a Dependency

1. Add version to `[versions]` in `gradle/libs.versions.toml`
2. Add library to `[libraries]` with `version.ref`
3. Use in module: `implementation(libs.your.library)`

```toml
# gradle/libs.versions.toml
[versions]
your-lib = "1.0.0"

[libraries]
your-library = { module = "com.example:library", version.ref = "your-lib" }
```

Never hardcode versions in `build.gradle.kts` files.

## Adding a New Module

1. Create module directory under appropriate parent (`core/`, `feature/`, etc.)
2. Create `build.gradle.kts` applying convention plugin:
   ```kotlin
   plugins {
       id("ripdpi.android.library")
   }
   android { namespace = "com.poyka.ripdpi.your.module" }
   ```
3. Add `include(":your:module")` to `settings.gradle.kts`
4. Add Compose plugin if needed: `id("ripdpi.android.compose")`

## Properties (Single Source of Truth)

All in `gradle.properties`:

| Property | Meaning |
|----------|---------|
| `ripdpi.compileSdk` | Android compile SDK; read the live value before selecting SDK packages or APIs |
| `ripdpi.minSdk` | Minimum supported Android API |
| `ripdpi.targetSdk` | Runtime-behavior target; changes require separate compatibility testing |
| `ripdpi.nativeNdkVersion` | Pinned Android NDK version |
| `ripdpi.nativeAbis` | Full CI/release ABI set |
| `ripdpi.localNativeAbisDefault` | Local ABI policy; `host` derives the ABI from the workstation architecture |

## Gotchas

- **build-logic is an included build** (`includeBuild("build-logic")` in settings). Changes to convention plugins require re-sync.
- **Static analysis**: Run `./gradlew staticAnalysis` -- it aggregates detekt, ktlint, and Android lint for the quality-enabled Android modules.
- **Native build order**: `:core:engine:buildRustNativeLibs` runs before `preBuild`. If native build fails, check NDK installation path, Rust target availability, and the `ripdpi.android.rust-native` convention plugin under `build-logic/convention/`.
- **Local ABI fast path**: local non-release builds follow `ripdpi.localNativeAbisDefault`; when it is `host`, Apple Silicon resolves to `arm64-v8a` and Intel hosts resolve to `x86_64`. Use `-Pripdpi.localNativeAbis=x86_64` for emulator-focused iteration.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Adding plugin directly to module | Use convention plugin from `build-logic/` |
| Hardcoding version in build.gradle.kts | Add to version catalog `libs.versions.toml` |
| Changing SDK versions in module | Change in `gradle.properties`, convention plugins read it |
| Missing namespace in new library module | Required: `android { namespace = "com.poyka.ripdpi..." }` |
