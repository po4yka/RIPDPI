---
name: release-changelog
description: Release prep, changelog generation, version bumps, Play Store notes, tags, and release notes.
---

# Release Changelog (RIPDPI)

## Release Workflow

The release pipeline lives in `.github/workflows/release.yml`.

**Triggers:**
- Push a tag matching `v*` (e.g., `v0.1.0`) -- automatic release
- Manual dispatch via `workflow_dispatch` with optional `create_release` boolean

**Steps (read the current `release` job before use):**
1. Sets up Java 17, Rust stable, Android SDK, NDK (version from `gradle.properties`)
2. Decodes release keystore from `KEYSTORE_BASE64` secret
3. Builds `bundlePlayFullRelease`, `assembleFdroidFullRelease`, and `assembleGithubFullRelease` with signing env vars
4. Uploads AAB, APK, R8 mapping, native symbols (90-day retention)
5. Creates GitHub Release via the exact pinned `softprops/action-gh-release` SHA (v3.0.1 at this review) with
   `generate_release_notes: true`

**Artifacts:** Play AAB under `app/build/outputs/bundle/playFullRelease/` and
Fdroid/Github APKs under their flavor-qualified `app/build/outputs/apk/*/release/` directories,
APK, R8 mappings at `app/build/outputs/mapping/*Release/mapping.txt`, and the
packaged `release-native-symbols.zip` plus manifest.

**Required secrets:** `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Version Management

Version code and name are defined in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = 11        // current snapshot; read live before changing
    versionName = "0.1.3"  // current snapshot; read live before changing
}
```

The convention plugin at `build-logic/convention/.../ripdpi.android.application.gradle.kts`
names universal artifacts as `RIPDPI-$versionName-$versionCode-$buildTypeName-universal`. `BuildConfig.VERSION_NAME`
appends `-debug` or `-bench` suffixes for non-release builds.

**How to bump:** Edit `app/build.gradle.kts` (the only location), increment `versionCode`,
update `versionName`, and commit as `chore: bump version to X.Y.Z`. Creating or
pushing a tag is an external write and requires the user's explicit approval.

## Changelog Generation

The project uses conventional commits (see `git log --oneline`). Parse commits
between the previous tag and HEAD to generate a changelog.

**Commit prefix categories:**

| Prefix | Changelog Section |
|--------|-------------------|
| `feat` | New Features |
| `fix` | Bug Fixes |
| `perf` | Performance |
| `refactor` | Refactoring |
| `test` | Tests |
| `docs` | Documentation |
| `chore` | Maintenance |

**Steps to generate:**
1. Find previous tag: `git tag -l 'v*' --sort=-creatordate | head -1`
2. List commits: `git log "$(git tag -l 'v*' --sort=-creatordate | head -1)"..HEAD --oneline`
3. Group by prefix, omit `chore`/`test` from user-facing notes
4. Keep scope when it adds clarity: `fix(security): ...`

**Example:**
```
## v0.1.0
### New Features
- Integrate real biometric API, hardware detection, and app re-lock
### Bug Fixes
- Harden PIN lock with 7 defense-in-depth improvements
- Move read timeout after handshake in ws-tunnel
### Performance
- Optimize CI pipeline -- reduce wall-clock by ~10min
```

## Play Store Release Notes

The project does not currently have a `whatsnew/` directory or
`app/src/main/play/` listing files. When adding Play Store release notes:

**Directory structure (Triple-T Gradle Play Publisher convention):**
`app/src/main/play/release-notes/{en-US,ru-RU}/default.txt`

**Constraints:**
- Max 500 characters per locale file (Google Play rejects longer text)
- Plain text only, no markdown or HTML
- Write in the locale's language
- Focus on user-visible changes: new features, important fixes
- Skip internal changes (CI, refactoring, test updates)

**Tone:** Direct, concise, no marketing fluff. State what changed and why
it matters to the user.

## Pre-Release Checklist

1. All CI checks pass on `main` (`.github/workflows/ci.yml`)
2. Version bumped in `app/build.gradle.kts` (both `versionCode` and `versionName`)
3. Changelog reviewed -- no sensitive information in commit messages
4. Play Store release notes written for each supported locale (under 500 chars)
5. Signing secrets configured in GitHub repository settings
6. Tag created and pushed: `git tag vX.Y.Z && git push origin vX.Y.Z`
7. Verify the Release workflow completes and artifacts are uploaded
8. Download APK from GitHub Release, smoke-test on a physical device
9. If publishing to Play Store: upload AAB from release artifacts
