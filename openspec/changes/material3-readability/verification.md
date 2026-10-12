---
task_id: UIX-1791793531264679
change: material3-readability
commit_sha: null
local: required
local_evidence: null
remote_ci: not_applicable
remote_ci_evidence: Local implementation and main commits only; push is not requested.
device: not_applicable
device_evidence: Visual checks use fresh host-rendered images; no device run is claimed.
artifact: required
artifact_evidence: null
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
| REQ-UI-SHARED-ACTIONS | UIX-1791804419498505 | Shared action, feedback, navigation tests and fresh renders | Pending |
| REQ-UI-ADAPTIVE-CHOICES | UIX-1791804426740078 | Localized DNS, relay, and form layout tests and renders | Pending |
| REQ-UI-DATA-LAYOUT | UIX-1791804434249424 | Graph, import, recovery, and data layout tests and renders | Pending |

The app-wide combined review and visual checks are owned by UIX-1791804441168759. The first-stage results below are historical evidence at the stated commits; they do not prove the pending follow-up.

## First-stage local commands

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
