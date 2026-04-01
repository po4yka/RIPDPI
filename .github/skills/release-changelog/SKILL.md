---
name: release-changelog
description: >
  Release preparation, changelog generation, version bumping, and Play Store
  release notes for RIPDPI. Use when: preparing a release, bumping version
  code/name, generating a changelog from conventional commits, writing Play
  Store whatsnew text, creating a git tag, running the release workflow,
  reviewing what changed since last release, drafting GitHub release notes.
  Triggers on: release, changelog, version bump, whatsnew, play store notes,
  release notes, tag, ship, publish.
---

# Release Changelog (RIPDPI)

## Release Workflow

The release pipeline lives in `.github/workflows/release.yml`.

**Triggers:**
- Push a tag matching `v*` (e.g., `v0.1.0`) -- automatic release
- Manual dispatch via `workflow_dispatch` with optional `create_release` boolean

**Steps (single `release` job, `ubuntu-latest`):**
1. Sets up Java 17, Rust stable, Android SDK, NDK (version from `gradle.properties`)
2. Decodes release keystore from `KEYSTORE_BASE64` secret
3. Runs `./gradlew bundleRelease -Pripdpi.enableAbiSplits=false` then `./gradlew assembleRelease` with signing env vars
4. Uploads AAB, APK, R8 mapping, native symbols (90-day retention)
5. Creates GitHub Release via `softprops/action-gh-release@v2` with
   `generate_release_notes: true`

**Artifacts:** AAB at `app/build/outputs/bundle/release/RIPDPI-{versionName}-{versionCode}-release.aab`,
APK, R8 mapping at `mapping/release/mapping.txt`, native symbol tables.

**Required secrets:** `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Version Management

Version code and name are defined in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = 3        // monotonically increasing integer
    versionName = "0.0.3"  // semver string
}
```

The convention plugin at `build-logic/convention/.../ripdpi.android.application.gradle.kts`
names artifacts as `RIPDPI-$versionName-$versionCode-$buildTypeName`. `BuildConfig.VERSION_NAME`
appends `-debug` or `-bench` suffixes for non-release builds.

**How to bump:** Edit `app/build.gradle.kts` (the only location), increment `versionCode`,
update `versionName`, commit as `chore: bump version to X.Y.Z`, then tag and push:
`git tag vX.Y.Z && git push origin vX.Y.Z`.

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
2. List commits: `git log v0.0.3..HEAD --oneline`
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
