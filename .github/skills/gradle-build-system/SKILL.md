---
name: gradle-build-system
description: Gradle dependencies, modules, convention plugins, and Android build failure triage.
---

# Gradle Build System

## Overview

RIPDPI uses Gradle 9.4 with convention plugins in `build-logic/convention/` and a version catalog at `gradle/libs.versions.toml`. All build configuration flows through convention plugins -- never add raw plugin config to module build files.

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

| Property | Current Value |
|----------|---------------|
| `ripdpi.compileSdk` | 36 |
| `ripdpi.minSdk` | 27 |
| `ripdpi.targetSdk` | 35 |
| `ripdpi.nativeNdkVersion` | 29.0.14206865 |
| `ripdpi.nativeAbis` | armeabi-v7a,arm64-v8a,x86,x86_64 |
| `ripdpi.localNativeAbisDefault` | arm64-v8a |

## Gotchas

- **`android.newDsl=false`**: Workaround for protobuf-gradle-plugin 0.9.6 + AGP 9 incompatibility. Do not remove.
- **build-logic is an included build** (`includeBuild("build-logic")` in settings). Changes to convention plugins require re-sync.
- **Static analysis**: Run `./gradlew staticAnalysis` -- it aggregates detekt, ktlint, and Android lint for the quality-enabled Android modules.
- **Native build order**: `:core:engine:buildRustNativeLibs` runs before `preBuild`. If native build fails, check NDK installation path, Rust target availability, and the `ripdpi.android.rust-native` convention plugin under `build-logic/convention/`.
- **Local ABI fast path**: local non-release builds default to `ripdpi.localNativeAbisDefault=arm64-v8a`; use `-Pripdpi.localNativeAbis=x86_64` for emulator-focused iteration.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Adding plugin directly to module | Use convention plugin from `build-logic/` |
| Hardcoding version in build.gradle.kts | Add to version catalog `libs.versions.toml` |
| Changing SDK versions in module | Change in `gradle.properties`, convention plugins read it |
| Missing namespace in new library module | Required: `android { namespace = "com.poyka.ripdpi..." }` |
