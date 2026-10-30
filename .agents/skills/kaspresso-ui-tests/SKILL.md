---
name: kaspresso-ui-tests
description: "RIPDPI Kaspresso UI journeys: launch-contract presets, page objects on RipDpiTestTags, device runs, and failure triage. Use when writing, running, or debugging an androidTest UI journey."
---

# Kaspresso UI tests

UI journeys are Kaspresso instrumented tests in `app/src/androidTest`, grouped by area under
`com.poyka.ripdpi.ui.journeys.<area>` (settings, diagnostics, home).

## Write a journey

1. Extend `app/src/androidTest/kotlin/com/poyka/ripdpi/integration/RipDpiKaspressoTest.kt` and annotate the class
   with `@HiltAndroidTest`. The base orders `HiltAndroidRule` before the compose rule and injects in `@Before`.
2. Pass `automationLaunchIntent(...)` from `integration/AutomationTestSupport.kt` to open the screen under test with
   seeded presets. Preset values are the `Automation*Preset` enums in `app/src/main/kotlin/com/poyka/ripdpi/automation/`;
   `app/src/debug/kotlin/com/poyka/ripdpi/automation/DebugAutomationController.kt` shows what each preset seeds.
3. Model the screen as a private `ComposeScreen` whose nodes use `onNode { hasTestTag(...) }` with tags from
   `app/src/main/kotlin/com/poyka/ripdpi/ui/testing/RipDpiTestTags.kt`. Add a tag there only when no existing tag works.
4. Click with `scrollAndClick()`. Compose drops a click injected outside the viewport, and Kaspresso auto-scroll
   retries only checks. `performTextReplacement` is a semantics action and works off-screen.
5. Assert observable results: persisted values through an injected repository (`AppSettingsRepository.snapshot()`
   inside `composeRule.waitUntil`) or service state, with values that differ from the defaults. Do not re-test
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

Select tests with `package=` or one `class=`; a comma list in `class=` ran only the first class under the orchestrator.
Results: `app/build/outputs/androidTest-results/connected/**/TEST-*.xml` (file names contain spaces).

## Triage

| Symptom | Cause |
|---|---|
| Click has no effect, later wait times out | Node was off-screen; use `scrollAndClick()` |
| `Unable to resolve activity` for `MainActivity` | Broken device package state; retry on a `--temporary` device |
| `UnsatisfiedLinkError` for `libripdpi.so` | Run built with `-Pripdpi.skipNativeBuild=true` |
| `NoSuchMethodError` in `GeneratedMessageLite` | `protobuf-lite` leaked in; keep the exclusion on the Kaspresso dependencies in `app/build.gradle.kts` |
| `attr/drawerLayoutStyle not found` at resource link | Material leaked in; keep the Material exclusion there |
| Wrong screen at launch | `startRoute` is not a `Route.stableRoute` value, or the data preset lacks the screen's data |

Lab smokes that must keep the app running between steps (`test-lab/scripts/run-vpn-e2e.sh`) use
`test-lab/scripts/adb-ui-flow.py` from the host instead of instrumentation.
