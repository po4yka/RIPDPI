---
task_id: UIX-1791532861060606
change: connection-control-redesign
commit_sha: null
local: passed
local_evidence: 122 UI, resolver, and theme tests passed with no failures or skips. App and service lint and staticAnalysis passed in the native-less profile. Independent review found no remaining actionable defect.
remote_ci: required
remote_ci_evidence: The user authorized main integration and push. Hosted CI remains pending.
device: required
device_evidence: No device or emulator acceptance was performed.
artifact: blocked
artifact_evidence: Eight Compose renders on SDK 36 were inspected. The required SDK 37 render was rejected by the preview plugin, which supports SDK 21 through 36. A full APK build also lacks real libXray artifacts.
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
