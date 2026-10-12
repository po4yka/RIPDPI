---
task_id: UIX-1791793531264679
change: material3-readability
commit_sha: 73ffc07a52c777803983d1728626b39e788fddd6
local: passed
local_evidence: 447 UI and design-system tests passed with no failures, errors, or skips at d50c7ea6; app and service lint, app detekt and ktlint passed. Final Full and Simple Roborazzi verification passed with 554 executed tests and 30 existing variant assumption skips. Production and test sources are unchanged in the verified fixture commit. DESIGN validation and architecture health passed; locked Cargo metadata resolved 116 members. Source review and text-diff autoreview found no actionable defects.
remote_ci: not_applicable
remote_ci_evidence: Local implementation and main commits only; push is not requested.
device: not_applicable
device_evidence: Visual checks use fresh host-rendered images; no device run is claimed.
artifact: passed
artifact_evidence: 12 fresh light, dark, and 2x Compose renders were inspected; captures and SHA-256 values are in build/reports/material3-readability. All affected UI goldens were inspected by golden-blesser; full manifests, before and after images, XML, logs, and exact tree hashes are in the golden worktree build/reports/material3-goldens and build/reports/material3-row-icons. No device or live-network acceptance is claimed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UI-READABLE-TYPE | UIX-1791793632932195 | RipDpiReadabilityTokensTest, OnboardingOptionCardTest, and 12 fresh UI renders | Passed |
| REQ-UI-SETTINGS-LAYOUT | UIX-1791793633865680 | SettingsRowTest with long Russian and RTL values, leading icons, and chevrons at 320/420dp and 2x font | Passed |
| REQ-UI-DIAGNOSTICS-TABS | UIX-1791793634763649 | DiagnosticsSectionSwitcherTest: callbacks, selection, RTL, font scale, and text overflow; fresh renders | Passed |
| REQ-UI-PRESERVE-CONTRACTS | UIX-1791793635678588 | App/service lint, 447 UI/design-system tests, 12 renders, reviewed goldens, and final diff | Passed |

## Local commands

The combined UI gate passed at source commit `d50c7ea6c649df961cd9cbb0b01142765967014e`:

```sh
build-gate -- ./gradlew :app:testGithubFullDebugUnitTest \
  --tests 'com.poyka.ripdpi.ui.theme.*' \
  --tests 'com.poyka.ripdpi.ui.components.*' \
  --tests 'com.poyka.ripdpi.ui.navigation.*' \
  --tests 'com.poyka.ripdpi.ui.screens.home.*' \
  --tests 'com.poyka.ripdpi.ui.screens.diagnostics.*' \
  --tests 'com.poyka.ripdpi.ui.screens.profiles.*' \
  --tests 'com.poyka.ripdpi.ui.screens.onboarding.OnboardingOptionCardTest' \
  --tests 'com.poyka.ripdpi.ui.screens.onboarding.OnboardingScreenTest' \
  --tests 'com.poyka.ripdpi.ui.DesignSystemSourceRulesTest' \
  :app:lintGithubFullDebug :core:service:lintDebug :app:detekt :app:ktlintCheck --max-workers=4
```

The final fixture commit has the same production, debug, and test sources. Both screenshot variants passed with their existing flavor assumptions:

```sh
build-gate -- ./gradlew :app:verifyRoborazziGithubFullDebug :app:verifyRoborazziGithubSimpleDebug \
  -Pripdpi.skipNativeBuild=true -Pripdpi.includeRoborazziUnitTests=true \
  --tests 'com.poyka.ripdpi.ui.screenshot.*' --max-workers=4
```

The native-build flag is the repository CI setup for JVM screenshots. No APK was built with this flag. The Simple tests used the committed CI relay fixture as input; this does not prove a live network connection. The visual artifacts were rendered with:

```sh
build-gate -- ./gradlew :app:composePreviewRenderAll -PcomposePreview.filter=Material3Readability --max-workers=4
```

The source tree review covers the initial implementation and the text changes after the first fixture commit. The automated helper cannot read binary PNG changes; golden-blesser reviewed all affected image hashes separately. The final verified app tree is `1372b7b37c9c16640db4970b91879e9ac3abd192`; its screenshot tree is `3d30039998c0b3df5199cc0d23e2519bd2d8de26`.
