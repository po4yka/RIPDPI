# Android Distribution Channels

RIPDPI has one Android distribution flavor dimension named `distribution` with three product flavors:

- `play` for Google Play delivery.
- `fdroid` for F-Droid or compatible clients.
- `github` for GitHub Releases with an embedded user-consented APK updater.

All channels share the same `versionCode` and `versionName` from `app/build.gradle.kts`. Keep `versionCode` monotonically increasing across every channel; never run per-channel version-code sequences.

## Build Commands

Use these release tasks:

```bash
./gradlew bundlePlayFullRelease -Pripdpi.enableAbiSplits=false
./gradlew assembleFdroidFullRelease -Pripdpi.enableAbiSplits=false
./gradlew assembleGithubFullRelease -Pripdpi.enableAbiSplits=false
```

Outputs:

- Play AAB: `app/build/outputs/bundle/playFullRelease/*.aab`
- F-Droid APK: `app/build/outputs/apk/fdroidFull/release/*.apk`
- GitHub APK: `app/build/outputs/apk/githubFull/release/*.apk`

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

## Application Identity Review

Every app release has a blocking package-identity review in `quality/release-gates/app-identity-review.json`. The review covers every resolved `release` variant, including the non-published `simple` variants, and separately records the published `full` variants built by `.github/workflows/release-candidate.yml`. `:app:writeReleaseIdentityManifest` obtains final application IDs from the Android Components API, so the gate does not infer them by parsing Gradle source.

The current `com.poyka.ripdpi` identity is not an exact match in the reviewed circumvention-tool catalog, but it is not hidden: `ripdpi` and `dpi` are recognizable tokens, and an Android 11+ detector can make the package visible by naming the exact ID in `<queries>`. The accepted baseline therefore classifies the identity as `elevated` and `self-identifying`, not opaque. Changing the application ID would create a different Android app and break in-place Play, F-Droid, and GitHub update continuity even if signing keys remain unchanged.

For every version bump:

1. Re-read the `app-level-vpn-detection`, `mintsifry-vpn-detection-methodology`, and referenced RKNHardering catalog sources recorded in the review; update each source revision, blob hash, and `reviewedAt` date.
2. Refresh `catalog.packageIds`, run `./gradlew :app:writeReleaseIdentityManifest`, and compare the generated `app/build/reports/app-identity/release-identity.json` with the checked-in variants.
3. Update `reviewedRelease`, derived exact matches, recognizable tokens, risk level, and the explicit decision. A known match must use either `accept-known-match` with an accepted-risk rationale or `change-id` with a migration plan.
4. Run `python3 -m unittest scripts.tests.test_app_identity_review` and `python3 scripts/ci/check_app_identity_review.py`. CI and the tag release workflow repeat both the resolved-identity generation and the blocking check without fetching threat intelligence from the network.

The default decision is `retain-stable-id` only while the reviewed catalog has no exact match. Randomized IDs, automatic rotation, and alternate sideload identities are not part of the release pipeline.

## Google Play Flow

1. Build `bundlePlayFullRelease`.
2. Upload the generated AAB to Google Play.
3. Let Google Play own update discovery, staging, rollout, and installation.

The Play flavor must not request `android.permission.REQUEST_INSTALL_PACKAGES` and must not expose APK sideloading actions.

## F-Droid Flow

1. Build `assembleFdroidFullRelease`.
2. Publish the generated APK through F-Droid metadata or a compatible repository.
3. Let the client own update discovery, download, verification, and installation.

The F-Droid flavor intentionally disables the GitHub updater because F-Droid clients provide the update trust model. Keeping GitHub APK logic out of this flavor also avoids an extra package-install permission and a second update authority.

## GitHub Releases Flow

1. Build `assembleGithubFullRelease`.
2. Generate metadata:

   ```bash
   scripts/ci/generate_update_metadata.py \
     --apk-glob "app/build/outputs/apk/githubFull/release/*.apk" \
     --output-metadata "app/build/outputs/apk/githubFull/release/output-metadata.json" \
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

The release flow is defined by
`quality/release-gates/release-contract.json`. A manual dispatch of
`.github/workflows/release-candidate.yml` on `main` first requires successful
exact-SHA CI, then builds the signed outputs once:

- `bundlePlayFullRelease`
- `assembleFdroidFullRelease`
- `assembleGithubFullRelease`
- `update.json`
- `SHA256SUMS`

Before that first candidate, set `RIPDPI_RELEASE_WINDOW_START_SHA` to the exact
feature-freeze commit on `main` and `RIPDPI_RELEASE_WINDOW_STARTED_AT` to an
ISO-8601 UTC timestamp. Candidate preflight enforces the checked-in
`releaseWindow`: no more than 72 hours, 20 release-fix commits, or five candidate
runs for the target tag. Late features and refactors require a new release cut;
they cannot enter the signing environment as release fixes.

Run the corresponding full local, secret-free mirror before integration:

```bash
just release-preflight vX.Y.Z <window-start-sha> <window-started-at-utc>
```

The command emits `build/reports/release/preflight.json` only after release
contract, window, identity, architecture, locked Cargo metadata, TLS snapshot,
GithubFullRelease, release AndroidTest, native ELF, and mapping checks pass. The
receipt is deliberately bounded: it is host-ABI evidence, does not sign release
artifacts, and does not replace successful hosted `ci-required` for the exact
candidate SHA. It requires authenticated GitHub access to count the complete
release-candidate run history; it fails rather than reporting an unverified zero.

For manual candidate or publication downloads, wrap the complete download and
verification command with `scripts/ci/with-transient-release-downloads.sh` and
write only below its `RIPDPI_RELEASE_DOWNLOAD_DIR`. The helper removes that exact
managed download directory on exit, including failure paths.

The candidate is stored with a source-bound manifest, signatures, native ELF
checks, symbols, and attestations. A matching `v*` tag then triggers
`.github/workflows/release.yml`. That workflow downloads the candidate by its
exact run ID, reverifies the source SHA and inventory, creates SBOMs and the
publish bundle, and attaches those existing bytes to the GitHub Release. It does
not rebuild application binaries.

Run `python3 scripts/ci/check_release_contract.py` after changing either workflow
or release guidance. The validator rejects trigger, input, workflow-path, and
maintained-documentation drift.

Release assurance is reported separately:

- `artifact-publish` is the automated release-blocking profile;
- `device-qualified` adds separately recorded emulator, physical-device, or
  owner-lab evidence;
- `owner-accepted` records an explicit owner acceptance of named evidence gaps
  without converting missing or failing checks into PASS.
