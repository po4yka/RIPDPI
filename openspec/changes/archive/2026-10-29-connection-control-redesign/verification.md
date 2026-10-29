---
task_id: UIX-1791532861060606
change: connection-control-redesign
commit_sha: 061441d2acae1634870aaa9c686c0990884bd1d8
local: passed
local_evidence: 122 UI, resolver, and theme tests passed with no failures or skips. App and service lint and staticAnalysis passed in the native-less profile. Independent review found no remaining actionable defect.
remote_ci: passed
remote_ci_evidence: Manual full CI run 40831691401 at c9d5727ef, which contains this change, passed all 50 executed jobs, including verify-roborazzi, gradle-static-analysis, kotlin-coverage, debug builds for three flavors, and instrumented tests on API 27, 33, 35, 36, and 37.
device: passed
device_evidence: Real native githubFull debug APKs from fd92c013c and 061441d2a ran on leased RIPDPI_Pool_2 (API 35 ARM64, 320dp at 160dpi) with a real local VPN connection. Connect, cancel, and guarded disconnect, non-interactive route text, pending feedback without stage marks, strategy details, and 2x font in English and Arabic in both themes were observed. Error states, keyboard, TalkBack, lockdown, and reconnect review were not observed on a device; host tests cover them.
artifact: passed
artifact_evidence: Hosted verify-roborazzi passed in run 40831691401 at c9d5727ef. Device captures and UI dumps with SHA-256 manifests were retained outside the repository for both device passes.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

Observed on 2026-10-09 in the isolated `feat/connection-control-redesign` worktree.
The task remains in review. Local checks do not establish device or hosted CI acceptance.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HCC-ACTION | UIX-1791533045357434 | Component tests cover all five states, connect/retry/cancel, and guarded disconnect. Error with a running service uses STOP. | passed |
| REQ-HCC-ROUTE | UIX-1791533045357434 | Route and exit have readable text semantics outside the button and no click action. | passed |
| REQ-HCC-EVIDENCE | UIX-1791533044507020 | Resolver tests retain pending stages at 0, 2500, and 60000 ms. Startup displays no completed stage indicators. | passed |
| REQ-HCC-INPUT | UIX-1791533046236328 | Touch, accessibility, keyboard, expiry, state reset, swipes, lockdown, native key repeat, and canceled release tests pass. | passed |
| REQ-HCC-ADAPT | UIX-1791533047120589 | The SDK 35 test checks 320dp, fontScale 2.0, RTL, text overflow, ellipsis, and bounds. Eight SDK 36 previews were inspected. Target SDK 37 remains blocked. | passed |
| REQ-HCC-RESEARCH | UIX-1791533043597405 | NordVPN widgets and Opera VPN panel images were retrieved and inspected through Mobbin MCP. Links and adopted patterns are recorded in design.md. | passed |

## Executed checks

The final invocation passed in 6 minutes 10 seconds with 775 task outcomes.
JUnit XML reports contain 122 tests, zero failures, zero errors, and zero skips.

All commands used `build-gate -- ./gradlew`, four workers, native CPU budget 4,
`-Pripdpi.skipNativeBuild=true`, Gradle heap 5 GiB, and Kotlin heap 3 GiB.
The final command used `--offline` and these tasks and test filters:

```text
:app:testGithubFullDebugUnitTest
  --tests com.poyka.ripdpi.ui.components.inputs.RipDpiConnectionActuator*Test
  --tests com.poyka.ripdpi.activities.MainViewModelTest
  --tests com.poyka.ripdpi.ui.theme.*
:app:lintGithubFullDebug
:core:service:lintDebug
staticAnalysis
:app:composePreviewRenderAll
  -PcomposePreview.filter=ConnectionActuator
  --init-script build/connection-redesign/preview-sdk36.gradle
```

The native-less profile supplies test-only dependency artifacts. The changed
Compose and resolver code was compiled and executed. These results do not
validate real native artifacts, APK packaging, service integration, or traffic.

The first online lint invocation waited on Maven metadata connections and was
stopped. The final offline invocation retained the same lint and static checks.
The final Gradle JVM options also set network connect/read timeouts to 15000 ms.
All ten actuator resource sets were updated. Locale lint passed.

The temporary, ignored SDK 36 init script sets the existing plugin extension:

```groovy
allprojects { target ->
    target.pluginManager.withPlugin('ee.schimke.composeai.preview') {
        if (target.path == ':app') {
            def preview = target.extensions.getByName('composePreview')
            preview.variant.set('githubFullDebug')
            preview.sdkVersion.set(36)
        }
    }
}
```

The required SDK 37 attempt used the same variant with `sdkVersion` derived from
`ripdpi.targetSdk`. It failed at `composePreviewGenerateRobolectricProperties`
because the installed plugin supports SDK 21 through 36. The compatibility
render does not establish target SDK 37 acceptance. No project SDK or plugin
configuration was changed.

The full native preflight `:core:engine:verifyLibXrayArtifacts` failed because
`native/xray/artifacts` is absent. No real libxray.aar was available in a usable
local checkout. An installable APK and device acceptance remain unavailable.

## Visual inspection

The eight new previews show disconnected, connecting, connected, warning,
fault, dark theme, Arabic RTL at fontScale 2.0, and Android lockdown at
fontScale 2.0. Their 360dp frames have 320dp content widths. Status, route,
button labels, and fault detail fit their surfaces. No overlap or clipping was
observed. The large-text route uses full-width stacked labels.

Temporary PNGs are under `app/build/compose-previews/renders/`. Execution logs
are copied to `build/connection-redesign/`. Neither location is committed.
The conversation prototype is separate from these actual Compose renders.

## Review and residual checks

- Independent review found the key-repeat boundary. A native Android key event regression failed before the fix and passed afterward.
- The final review found no remaining actionable defect. `git diff --check` passed.
- The old source-text rail assertion was removed with the retired rail. New behavior tests cover the replacement. No gate or baseline was removed.
- Roborazzi golden fixtures were not changed or blessed. Golden verification and device acceptance remain separate follow-up gates.
- `build-gate` was installed locally and checked for serialization, occupied status, exit-code propagation, and nested invocation. It is available at `/opt/homebrew/bin/build-gate`.
- No push, merge, PR, release, or deployment was performed.


## Rebased integration checks

On 2026-10-09 the two scoped commits were rebased without conflicts onto origin/main c2baf9931. The implementation SHA became 3a9e950ee. The same 122 targeted tests, app/service locale lint, and staticAnalysis passed on the rebased tree in 5 minutes 20 seconds (766 tasks). Architecture health reported no new or worsened indicators; locked Cargo metadata passed. The UI profile still omits real native artifacts.

The combined Full/Simple Roborazzi command stopped at verifyEmbeddedRelayBundle: Simple requires a non-empty src/simple/assets/embedded-relay-bundle.json. No placeholder bundle was created and the gate was not removed. A Full-only golden check is pending.


The Full-only Roborazzi check ran on SDK 35 and produced expected/actual/compare images. Home scenes differ because the rail and timer stage display were removed. The transition scene still had old hard-coded secure-line labels; its source now uses Connecting, Relay, and Cancel connection, with no retired stage fixture. The updated actual image was inspected at fontScale 2.0. Golden PNGs remain unchanged; explicit authorization for affected fixtures is still required. Setup-health text differences also include changes already present on main and must be classified before any fixture update.

## Closure evidence (2026-10-29)

Hosted CI: manual full run 40831691401 at `c9d5727ef890568c104e04e487623ec6d2e2227e` passed all 50 executed jobs with no failures. It replaces the earlier pending push run.

Artifact: the SDK 37 preview limitation no longer blocks evidence. Hosted `verify-roborazzi` passed, and installed captures replace preview renders.

Device: two passes ran on the leased `RIPDPI_Pool_2` emulator (API 35, ARM64, `google_apis_playstore`, 320x640 at 160dpi). The real native APKs were built with a verified prebuilt libXray AAR through `-Pripdpi.prebuiltXrayAarDir`:

- `fd92c013c`: APK SHA-256 `16f91fefbf60795b6223c06efe4163ed4f0440c2b57f8e69b6b08c9b4c7de8c9`.
- `061441d2a`: APK SHA-256 `6cb6115e3a0a2e2423a7e829acf3c07077ae40b789e9b25ecfec8788e14f7fe7`.

A real local VPN connection was established.

Observed:

- Disconnected shows Connect, Connecting shows a visible Cancel, and Connected shows Disconnect. These are separate click targets.
- Disconnect asks for confirmation. Stay connected keeps the session, and confirm disconnects.
- The route and exit labels are text without a click action.
- Connecting shows a spinner with text and no stage marks.
- Strategy details expand.
- At 2x font, light and dark in English and Arabic, nothing clips or overlaps.

The first pass found an untranslated Arabic strategy value. Commit `061441d2a` fixed it, and the second pass observed the localized value in Arabic and Russian.

Not observed on a device: error states, the 5 s pending state, keyboard and TalkBack activation, lockdown, the 4 s expiry, and reconnect review. The recorded host tests cover them. No physical device was used.
