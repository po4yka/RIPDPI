---
name: release-signing
description: 'Reference for RIPDPI release signing config and versioning. Use when touching signing config or version bumps. Keep rules: r8-jni-keep-rules. Tagging and publishing: ripdpi-release.'
---

# Release Signing

End-to-end release pipeline: signing config, R8/ProGuard, versioning, artifact naming.

**Release flow:** manually dispatch `.github/workflows/release-candidate.yml` for
an exact `main` SHA, then push the matching `v*` tag to trigger
`.github/workflows/release.yml`, which promotes the immutable candidate.

## Signing Configuration

Four environment variables, read via Gradle providers in `app/build.gradle.kts`:

| Variable | Purpose |
|----------|---------|
| `RIPDPI_SIGNING_STORE_FILE` | Path to decoded keystore file |
| `RIPDPI_SIGNING_STORE_PASSWORD` | Keystore password |
| `RIPDPI_SIGNING_KEY_ALIAS` | Key alias within the keystore |
| `RIPDPI_SIGNING_KEY_PASSWORD` | Key password |

**Lookup order:** `app/build.gradle.kts` resolves each signing value through a `localOrEnv(propKey, envKey)` helper in the `signingConfigs { }` block: it reads `local.properties` first (keys `signing.storeFile`, `signing.storePassword`, `signing.keyAlias`, `signing.keyPassword`), falling back to the matching environment variable only when the property is absent. The `release` signing config is created only when the resolved store-file path is non-null -- a build with neither source configured skips signing.

**CI flow:** the keystore is stored as base64 in GitHub secret `KEYSTORE_BASE64`; `release-candidate.yml` decodes it to a temp file and exports `RIPDPI_SIGNING_STORE_FILE` (and the matching password/alias variables) so `localOrEnv()` resolves them from the environment. `release.yml` never touches the keystore -- it only promotes an already-signed candidate.

Never commit keystores to the repository.

## R8 / ProGuard Rules

Three layers of rules, evaluated together at build time:

| File | Purpose |
|------|---------|
| `app/proguard-rules.pro` | App-level rules (intentionally minimal -- relies on library consumer rules) |
| `core/data/consumer-rules.pro` | Preserves protobuf lite classes: `com.poyka.ripdpi.proto.**` |
| `core/engine/consumer-rules.pro` | Preserves each JNI binding class with `-keepclasseswithmembernames`, plus a couple of by-name-callback keep blocks; see the file for the current entries |

### R8 Diagnostics

Enable with `-Pripdpi.r8Diagnostics=true` to generate analysis files:

```bash
./gradlew assembleGithubFullRelease -Pripdpi.r8Diagnostics=true
```

Output in `app/build/outputs/mapping/githubFullRelease/` for that task:
- `usage.txt` -- Classes/methods removed by R8
- `seeds.txt` -- Classes/methods kept by keep rules
- `configuration.txt` -- Merged R8 configuration

Configured in `ripdpi.android.application.gradle.kts`.

### Adding JNI Consumer Rules

When adding a new JNI binding class, add a keep rule to the module's `consumer-rules.pro` using the same shape as the existing entries:

```proguard
-keepclasseswithmembernames class com.poyka.ripdpi.core.NewNativeBindings {
    native <methods>;
}
```

A Kotlin callback invoked by name from native code (not a native method itself) needs a different keep shape; see the `.agents/skills/r8-jni-keep-rules/SKILL.md` decision tree for that case and for the general JNI keep-rule failure modes.

## Versioning

| Property | Location | Current |
|----------|----------|---------|
| `versionCode` | `app/build.gradle.kts` | read `ripdpiVersionCode` live |
| `versionName` | `app/build.gradle.kts` | read `ripdpiVersionName` live |

**Artifact naming pattern** (from `ripdpi.android.application.gradle.kts`):

```
RIPDPI-{versionName}-{versionCode}-{buildType}-universal.aab
RIPDPI-{versionName}-{versionCode}-{buildType}-universal.apk
```

Version bumping checklist:
1. Update `versionCode` (must increment for every Play Store upload).
2. Update `versionName` (semantic versioning).
3. Do not tag or push directly -- hand off to the `ripdpi-release` skill, which enforces the release-window cut and the candidate-dispatch sequence before any tag exists.

## Release Artifacts

The release-candidate workflow builds and uploads:

| Artifact | Path |
|----------|------|
| Play AAB | `app/build/outputs/bundle/playFullRelease/*.aab` |
| F-Droid APK | `app/build/outputs/apk/fdroidFull/release/*.apk` |
| GitHub APK | `app/build/outputs/apk/githubFull/release/*.apk` |
| R8 Mapping | `app/build/outputs/mapping/*Release/mapping.txt` |
| Compose Mapping | Compose stability report |
| Native Symbols | packaged `release-native-symbols/manifest.json` and `release-native-symbols.zip` |

The tag-triggered publication workflow downloads the candidate by its exact run
ID, reverifies its manifest and SHA, stages checksums and SBOMs, and uses the
pinned `softprops/action-gh-release` action without rebuilding app binaries.

## Release Verification in CI

The `release-verification` job in `ci.yml` builds all minified release variants
and, on the GitHub shard, assembles the `GithubFullReleaseAndroidTest` APK with
`testBuildType=release`. It uses prebuilt native inputs and no signing secrets, so
release-only Hilt, test ownership, and shrinker problems fail in ordinary CI
before the signed-candidate environment. This catches:
- Missing keep rules for JNI classes
- R8 stripping classes accessed via reflection
- ProGuard rule conflicts
- Release-only instrumentation/Hilt compilation failures

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| New JNI class without consumer-rules | R8 strips the class, native code crashes. Add keep rule to module's `consumer-rules.pro`. |
| Committing keystore to repo | Use environment variables + base64 encoding in CI secrets. |
| Same `versionCode` as previous release | Play Store rejects. Always increment `versionCode`. |
| Skipping R8 diagnostics check | Run with `-Pripdpi.r8Diagnostics=true` before release to verify keep rules. |
| Adding keep rules to `app/proguard-rules.pro` | Use module-level `consumer-rules.pro` so rules travel with the module. |
| Forgetting native symbol upload | Crash reports will lack native stack traces. Verify the packaged `release-native-symbols.zip` and manifest upload. |

## See Also

- `.agents/skills/ripdpi-release/SKILL.md` -- the gated tag/publish workflow this skill hands off to
- `.agents/skills/r8-jni-keep-rules/SKILL.md` -- general JNI/route/proto keep-rule decision tree
- `.agents/skills/ci-workflow-authoring/SKILL.md` -- CI pipeline guidance
- `quality/release-gates/release-contract.json` -- machine-readable release flow
- `.github/workflows/release-candidate.yml` -- signed candidate production
- `.github/workflows/release.yml` -- tag-bound candidate promotion
