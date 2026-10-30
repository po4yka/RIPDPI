---
name: kaspresso-ui-tests
description: "RIPDPI Kaspresso UI journeys: launch-contract presets, page objects on RipDpiTestTags, device runs, and failure triage. Use when writing, running, or debugging an androidTest UI journey."
---

# Kaspresso UI tests

UI journeys are Kaspresso instrumented tests in `app/src/androidTest`, grouped by area under
`com.poyka.ripdpi.ui.journeys.<area>` (settings, diagnostics, home).

Two kinds of UI instrumentation live side by side:

- Journeys (`ui/journeys/`) start the real `MainActivity` through the automation launch contract and walk a user flow
  across screens with seeded data.
- Component acceptance tests (`ui/*InstrumentedTest.kt`, `ui/HomeLatencyDetailsAcceptanceTest.kt`) render one
  component with `createComposeRule().setContent { ... }` and fixed state to check motion policy, RTL, font scale,
  pixels or TalkBack. Keep them there: a full-app journey cannot pin those conditions.

## Write a journey

1. Extend `app/src/androidTest/kotlin/com/poyka/ripdpi/integration/RipDpiKaspressoTest.kt` and annotate the class
   with `@HiltAndroidTest`. The base orders `HiltAndroidRule` before the compose rule and injects in `@Before`.
2. Pass `automationLaunchIntent(...)` from `integration/AutomationTestSupport.kt` to open the screen under test with
   seeded presets. Preset values are the `Automation*Preset` enums in `app/src/main/kotlin/com/poyka/ripdpi/automation/`;
   `app/src/debug/kotlin/com/poyka/ripdpi/automation/DebugAutomationController.kt` shows what each preset seeds.
3. Model each screen as a page object that extends `JourneyPage` from
   `app/src/androidTest/kotlin/com/poyka/ripdpi/ui/journeys/JourneyPage.kt`, in the area's `<Area>Pages.kt`.
   Its properties are tags from `app/src/main/kotlin/com/poyka/ripdpi/ui/testing/RipDpiTestTags.kt`. Add a tag
   there only when no existing tag works.
4. Act through the page: `with(ConfigPage(composeRule)) { click(vpnChip); awaitSelected(vpnChip) }`. The page actions
   first scroll lazy lists until the item is composed and in view, because Compose drops a click outside the
   viewport. Use `node(tag)` for Kakao assertions and `performTextReplacement`.
5. Assert observable results: persisted values through an injected repository (`AppSettingsRepository.snapshot()`
   through `composeRule.waitForSettings`) or service state, with values that differ from the defaults. Do not re-test
   navigation that `MainActivityNavigationInstrumentedTest` covers.

`app/src/androidTest/kotlin/com/poyka/ripdpi/ui/journeys/settings/DnsSettingsKaspressoTest.kt` is a compact example.

## Run on a device

Use a lease from `docs/contributor/android-emulator-pool.md`. The app loads its native libraries at startup, so the
device run needs the native build (`-Pripdpi.skipNativeBuild=true` is compile-only):

```bash
python3 scripts/ci/android-avd-pool.py run --api 37 --arch arm64-v8a --target google_apis \
  --profile pixel_10_pro_xl -- ./gradlew :app:connectedGithubFullDebugAndroidTest \
  -Pripdpi.localNativeAbis=arm64-v8a \
  -Pandroid.testInstrumentationRunnerArguments.package=com.poyka.ripdpi.ui.journeys.settings
```

Select tests with `package=` or one `class=`; a comma list in the Gradle `class=` property ran only the first class.
Results: `app/build/outputs/androidTest-results/connected/**/TEST-*.xml` (file names contain spaces).

## Failure artifacts

`RipDpiKaspressoTest` adds Kaspresso interceptors that, on a failed step, save a screenshot and the view hierarchy to
`/sdcard/Documents/{screenshots,view_hierarchy}/<class>/<test>/`. They survive app uninstall; pull them with
`adb pull /sdcard/Documents`. CI pulls them into the journey artifacts. Timeouts from `waitForTag` and other
`waitWithTree` waits also put the Compose semantics tree in the failure message.

## Triage

| Symptom | Cause |
|---|---|
| Click has no effect, later wait times out | Node was off-screen; act through the page (`click(tag)`), not a raw `performClick()` |
| `Unable to resolve activity` for `MainActivity` | Broken device package state; retry on a `--temporary` device |
| `UnsatisfiedLinkError` for `libripdpi.so` | Run built with `-Pripdpi.skipNativeBuild=true` |
| `NoSuchMethodError` in `GeneratedMessageLite` | `protobuf-lite` leaked in; keep the exclusion on the Kaspresso dependencies in `app/build.gradle.kts` |
| `attr/drawerLayoutStyle not found` at resource link | Material leaked in; keep the Material exclusion there |
| Wrong screen at launch | `startRoute` is not a `Route.stableRoute` value, or the data preset lacks the screen's data |

## CI

- Pull requests: the `android-instrumented-tests` job runs a smoke set of six journeys on the API 37 emulator.
  The list is in the "Run Kaspresso UI journey smoke" step of `.github/workflows/ci.yml`; renaming a listed test
  needs the same change there.
- Nightly (`41 2 * * *`) and manual runs with `run_ui_journeys`: the `android-ui-journeys` job runs the whole
  `com.poyka.ripdpi.ui.journeys` package on API 37 and API 31 emulators. Both use the orchestrator with `clearPackageData`.

Lab smokes that must keep the app running between steps (`test-lab/scripts/run-vpn-e2e.sh`) use
`test-lab/scripts/adb-ui-flow.py` from the host instead of instrumentation.
