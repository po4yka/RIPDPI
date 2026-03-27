---
name: release-signing
description: Use when building release APKs/AABs, configuring signing, managing ProGuard/R8 rules, bumping versions, or preparing Play Store uploads. Triggers on: release build, sign APK, keystore, ProGuard, R8, shrink, minify, version bump, release candidate.
---

# Release Signing

End-to-end release pipeline: signing config, R8/ProGuard, versioning, artifact naming.

**Release trigger:** Tag push matching `v*` or manual dispatch via `.github/workflows/release.yml`.

## Signing Configuration

Four environment variables, read via Gradle providers in `app/build.gradle.kts`:

| Variable | Purpose |
|----------|---------|
| `RIPDPI_SIGNING_STORE_FILE` | Path to decoded keystore file |
| `RIPDPI_SIGNING_STORE_PASSWORD` | Keystore password |
| `RIPDPI_SIGNING_KEY_ALIAS` | Key alias within the keystore |
| `RIPDPI_SIGNING_KEY_PASSWORD` | Key password |

**CI flow:** Keystore is stored as base64 in GitHub secret `KEYSTORE_BASE64`, decoded to a temp file at build time. The signing config in `app/build.gradle.kts` (lines 12-36) only creates the `release` signing config when `RIPDPI_SIGNING_STORE_FILE` is set -- local dev builds skip signing.

```kotlin
// app/build.gradle.kts pattern
val releaseStoreFilePath = providers.environmentVariable("RIPDPI_SIGNING_STORE_FILE")
signingConfigs {
    releaseStoreFilePath.orNull?.let { configuredStoreFile ->
        create("release") { storeFile = file(configuredStoreFile); ... }
    }
}
```

Never commit keystores to the repository.

## R8 / ProGuard Rules

Three layers of rules, evaluated together at build time:

| File | Purpose |
|------|---------|
| `app/proguard-rules.pro` | App-level rules (intentionally minimal -- relies on library consumer rules) |
| `core/data/consumer-rules.pro` | Preserves protobuf lite classes: `com.poyka.ripdpi.proto.**` |
| `core/engine/consumer-rules.pro` | Preserves JNI binding classes: `RipDpiProxyNativeBindings`, `Tun2SocksNativeBindings`, `NetworkDiagnosticsNativeBindings` |

### R8 Diagnostics

Enable with `-Pripdpi.r8Diagnostics=true` to generate analysis files:

```bash
./gradlew assembleRelease -Pripdpi.r8Diagnostics=true
```

Output in `app/build/outputs/mapping/release/`:
- `usage.txt` -- Classes/methods removed by R8
- `seeds.txt` -- Classes/methods kept by keep rules
- `configuration.txt` -- Merged R8 configuration

Configured in `ripdpi.android.application.gradle.kts`.

### Adding JNI Consumer Rules

When adding a new JNI binding class, add a keep rule to the module's `consumer-rules.pro`:

```proguard
-keep class com.poyka.ripdpi.core.engine.NewNativeBindings {
    native <methods>;
    # Keep any methods called from native code
    void onCallback(...);
}
```

## Versioning

| Property | Location | Current |
|----------|----------|---------|
| `versionCode` | `app/build.gradle.kts` | 1 |
| `versionName` | `app/build.gradle.kts` | "0.0.1" |

**Artifact naming pattern** (from `ripdpi.android.application.gradle.kts`):

```
RIPDPI-{versionName}-{versionCode}-{buildType}.aab
RIPDPI-{versionName}-{versionCode}-{buildType}.apk
```

Version bumping checklist:
1. Update `versionCode` (must increment for every Play Store upload)
2. Update `versionName` (semantic versioning)
3. Create a git tag: `git tag v{versionName}`
4. Push tag to trigger release: `git push origin v{versionName}`

## Release Artifacts

The release workflow uploads (90-day retention):

| Artifact | Path |
|----------|------|
| Release AAB | `app/build/outputs/bundle/release/*.aab` |
| Release APK | `app/build/outputs/apk/release/*.apk` |
| R8 Mapping | `app/build/outputs/mapping/release/mapping.txt` |
| Compose Mapping | Compose stability report |
| Native Symbols | `app/build/intermediates/native_symbol_tables/release/` |

A GitHub Release is created automatically via `softprops/action-gh-release@v2` with auto-generated notes.

## Release Verification in CI

The `release-verification` job in `ci.yml` builds a minified release APK on **every PR** to catch R8 issues early -- before they reach the release pipeline. This catches:
- Missing keep rules for JNI classes
- R8 stripping classes accessed via reflection
- ProGuard rule conflicts

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| New JNI class without consumer-rules | R8 strips the class, native code crashes. Add keep rule to module's `consumer-rules.pro`. |
| Committing keystore to repo | Use environment variables + base64 encoding in CI secrets. |
| Same `versionCode` as previous release | Play Store rejects. Always increment `versionCode`. |
| Skipping R8 diagnostics check | Run with `-Pripdpi.r8Diagnostics=true` before release to verify keep rules. |
| Adding keep rules to `app/proguard-rules.pro` | Use module-level `consumer-rules.pro` so rules travel with the module. |
| Forgetting native symbol upload | Crash reports will lack native stack traces. Verify `native_symbol_tables` artifact uploads. |

## See Also

- `.github/skills/ci-workflow-authoring/SKILL.md` -- CI pipeline that includes release verification
- `.github/workflows/release.yml` -- Full release workflow
