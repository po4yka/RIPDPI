# Android Distribution Channels

RIPDPI has one Android distribution flavor dimension named `distribution` with three product flavors:

- `play` for Google Play delivery.
- `fdroid` for F-Droid or compatible clients.
- `github` for GitHub Releases with an embedded user-consented APK updater.

All channels share the same `versionCode` and `versionName` from `app/build.gradle.kts`. Keep `versionCode` monotonically increasing across every channel; never run per-channel version-code sequences.

## Build Commands

Use these release tasks:

```bash
./gradlew bundlePlayRelease -Pripdpi.enableAbiSplits=false
./gradlew assembleFdroidRelease -Pripdpi.enableAbiSplits=false
./gradlew assembleGithubRelease -Pripdpi.enableAbiSplits=false
```

Outputs:

- Play AAB: `app/build/outputs/bundle/playRelease/*.aab`
- F-Droid APK: `app/build/outputs/apk/fdroid/release/*.apk`
- GitHub APK: `app/build/outputs/apk/github/release/*.apk`

## Channel Behavior

| Channel | Update authority | In-app APK install path | Package install permission |
| --- | --- | --- | --- |
| Google Play | Google Play | No | No |
| F-Droid | F-Droid or compatible client | No | No |
| GitHub Releases | Configured GitHub repository | Yes, after user consent | Yes, GitHub flavor only |

The shared About screen shows one Updates section. The flavor implementation decides whether in-app checks and installs are available:

- `play` reports that Google Play handles updates. The current adapter does not add Play update dependencies.
- `fdroid` reports that F-Droid or a compatible client handles updates. GitHub APK update code is not compiled into this flavor.
- `github` checks GitHub Releases, reads `update.json`, verifies the selected APK, and launches Android installer UI through a content URI.

## Signing Strategy

The application ID currently stays `com.poyka.ripdpi` across all channels. Cross-channel replacement works only when the installed app and the candidate update use the same signing certificate lineage. If Play App Signing uses a different signing lineage than APK channels, users cannot update between those channels in place even when the package name matches.

Release signing is still driven by:

- `RIPDPI_SIGNING_STORE_FILE`
- `RIPDPI_SIGNING_STORE_PASSWORD`
- `RIPDPI_SIGNING_KEY_ALIAS`
- `RIPDPI_SIGNING_KEY_PASSWORD`

The GitHub updater verifies package name, version code, version name, APK file-name safety, and SHA-256 before opening Android installer UI (`GithubUpdateMetadata.isStructurallyValid()`). `minSdk` is carried in the metadata when present but is not independently checked client-side — Android enforces `minSdk` and signing compatibility during installation.

## Google Play Flow

1. Build `bundlePlayRelease`.
2. Upload the generated AAB to Google Play.
3. Let Google Play own update discovery, staging, rollout, and installation.

The Play flavor must not request `android.permission.REQUEST_INSTALL_PACKAGES` and must not expose APK sideloading actions.

## F-Droid Flow

1. Build `assembleFdroidRelease`.
2. Publish the generated APK through F-Droid metadata or a compatible repository.
3. Let the client own update discovery, download, verification, and installation.

The F-Droid flavor intentionally disables the GitHub updater because F-Droid clients provide the update trust model. Keeping GitHub APK logic out of this flavor also avoids an extra package-install permission and a second update authority.

## GitHub Releases Flow

1. Build `assembleGithubRelease`.
2. Generate metadata:

   ```bash
   scripts/ci/generate_update_metadata.py \
     --apk-glob "app/build/outputs/apk/github/release/*.apk" \
     --output-metadata "app/build/outputs/apk/github/release/output-metadata.json" \
     --gradle-properties "gradle.properties" \
     --package-name "com.poyka.ripdpi" \
     --update-json "update.json" \
     --sha256sums "SHA256SUMS"
   ```

3. Attach the GitHub APK, `update.json`, and `SHA256SUMS` to the GitHub Release.

The app fetches `https://api.github.com/repos/po4yka/RIPDPI/releases/latest`, locates the `update.json` asset, parses it, selects the APK asset named by `apkName`, and downloads only release assets whose initial URL belongs to the configured `po4yka/RIPDPI` release path. Metadata must include:

```json
{
  "schemaVersion": 1,
  "packageName": "com.poyka.ripdpi",
  "versionCode": 8,
  "versionName": "0.0.8",
  "minSdk": 27,
  "apkName": "app-github-release.apk",
  "sha256": "<64 lowercase or uppercase hex characters>",
  "sizeBytes": 12345678,
  "changelog": "See GitHub release notes."
}
```

The GitHub flavor is the only flavor that declares `android.permission.REQUEST_INSTALL_PACKAGES` and the update APK `FileProvider`. Android 8 and newer may require the user to allow installs from this app before the standard installer UI can open. The app never attempts silent installation and never uses `file://` URIs.

## Release Automation

`.github/workflows/release.yml` builds all three release outputs in one run:

- `bundlePlayRelease`
- `assembleFdroidRelease`
- `assembleGithubRelease`
- `update.json`
- `SHA256SUMS`

The release workflow uploads all artifacts and attaches them to GitHub Releases when the workflow creates a release.
