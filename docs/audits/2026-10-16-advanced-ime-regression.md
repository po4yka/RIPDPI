# Advanced keyboard regression verification

Date: 2026-10-16. Application source: `3945363270c62a284feea927569f0f69e5b24f4d`.
API35/API37 and edit/save passed. API30 is blocked before boot by host disk space.
Main integration, remote push and CI are pending.

## Result and cause

The default Activity policy selected platform pan. A focused lower field made
Android move the Advanced toolbar under the status bar. Compose padding alone
reduced the viewport but did not stop platform pan. That first candidate failed
real-device acceptance and was not accepted as the fix.

MainActivity now declares `android:windowSoftInputMode="adjustResize"`. The shared
settings scaffold applies `imePadding()` once, so other settings forms also keep
their focused fields above the IME. Advanced has no duplicate padding. Back,
storage, service and navigation logic are unchanged.

The [official Compose setup](https://developer.android.com/develop/ui/compose/system/setup-e2e#configure-window)
requires the manifest attribute. The [official Android skill](https://github.com/android/skills/blob/main/system/edge-to-edge/SKILL.md)
distinguishes it from deprecated runtime constants. No production WindowManager
adjustment flag setter was added.

## Local checks

- Fresh native Full debug assembly passed; the APK has 13 native libraries.
- Inset regression RED: expected 620, actual 800 with a 180dp IME. It now passes.
- Manifest regression RED: expected resize (16), actual unspecified (0). Both
  MainActivity and its app-owned launcher aliases now pass.
- Full targeted tests: 226 passed. Simple targeted tests: 9 passed. Both have
  zero failures, errors and skips. Four inset cases cover font scale 1.0/2.0,
  parent system-bar consumption and parent IME consumption.
- Nine affected Roborazzi checks passed. No fixture was changed or recorded.
- Static analysis, Full/Simple app lint and service lint passed.
- DESIGN.md: zero violations. Architecture: 21 existing indicators, zero new,
  worsened or stale indicators. Locked Cargo metadata passed.
- Independent source, regression-test and Maestro reviews found no open issue.

Simple UI unit tests used the repository-owned structural CI bundle from
`scripts/fixtures/embedded-relay-bundle/ci-fixture.json`. This is UI evidence,
not relay connectivity. Device tests use the real native Full APK.

## Emulator acceptance

All focus cases use a 1080x1920 viewport at density 480. Insets come from the
actual device state. IME visibility must agree in two stable samples. The toolbar
and its 48dp Back target must remain below the status bar. The entire field must
stay above IME; its value must remain unchanged. First Back must hide IME, then
real toolbar Back must return to the expected route.

| API | Form and configuration | Result | Evidence directory under `/tmp/ripdpi-advanced-ime-device/` |
| --- | --- | --- | --- |
| 37 | Advanced, dark, font 2.0, three-button, direct entry | PASS | `20261016T171831Z` |
| 37 | ModeEditor, light, font 1.0, gestures, numeric field and footer | PASS | `20261016T171950Z` |
| 37 | StrategyConfig, light, font 1.0, gestures, multiline field and Save reachability | PASS | `20261016T172255Z` |
| 35 | Advanced, dark, font 2.0, three-button, direct entry | PASS | `20261016T181607Z` |
| 35 | Advanced, light, font 1.0, gestures, entry through Settings | PASS | `20261016T181607Z` |
| 30 | Same two Advanced cases | BLOCKED before boot | See startup logs below |

The API35 and API37 runs use Pixel 10 Pro XL profiles. On API37 the exact baseline
trigger put the title at y0..59 under a status bar ending at 156px and removed
Back. The accepted APK keeps Back at y168..312, title at y324..461 and the field
at y879..1023 above IME top 1023. The app window reports `adjust=resize`.

On API35 both cases pass with Back at y84..228 below status bar bottom 72;
the field is at y1011..1155 above IME top 1155. Return routes are Home and
Settings. Independent visual review found no toolbar clipping. Device font,
size, density, locale and navigation were restored; crash logs are empty.

The exact repository Maestro03 passed on API37: one test, zero failures.
An ordinary process restart retained the saved value 21. A companion UI flow
restored 14 and another restart confirmed 14. The flow now opens the Guided
Advanced accordion when required, hides the keyboard, checks the Advanced route
and scrolls Save into view before tapping it. Assertions and the persona remain.
Flow SHA256: `433e93de4470f0c3f632a9e73ebe650aca6c6ca19c9551bb315833b248176f05`.

## Preparation failures and remaining gate

Early API35/API30 starts failed before APK installation because normal userdata
allocation requires 12,288 MB. Later API35 tests initially lost Back to a
LeakCanary notification permission dialog. Logcat and independent review confirm
that timeline; those failures remain recorded and do not count as passing cases.
The final harness grants the declared notification permission only on fresh
owner-created test devices and checks MainActivity focus before focus and Back.
Geometry and Back assertions stay unchanged. An intermediate guard read the wrong
dump section; that attempt was stopped and its temporary AVD was removed.
Final focus harness v5 SHA256:
`e5d94d61888d73e4cc7aa35c91481c091c4b50ba8745233c01645a9d7093e64b`.

After explicit user approval and successful Save restoration, documented cleanup
removed only idle `RIPDPI_Pool_1`. Host space later fell again. API30 starts with
Pixel 10 Pro XL and the repository default Pixel 7 both failed before boot.
The latest available space was about 11,123 MB. No disk gate or userdata size was
changed. Owner wrappers removed their temporary AVDs. Separate approval to remove
idle `RIPDPI_Pool_2` is pending; it has not been removed.

Only this task's generated build outputs and a duplicate baseline output archive
were cleaned. All three canonical tested APKs and preserved reports remain;
APK hashes were rechecked. Source trees and foreign AVDs remain intact. A missing
`lsof` in one test PATH blocked automatic cleanup; documented cleanup removed
that one idle owner-created temporary AVD. Later PATHs include `/usr/sbin`.

## Reproduction artifacts

- Baseline APK SHA256: `60ab910586a6b226909d3f9cfc609c45f58418abb1e6230b304b737ca1a9d8fa`.
- Accepted APK SHA256: `755f97512d8da7ca35dd478356b83324922a1828dfc9d18fe1a7437a2a736975`.
- APK manifests and immutable copies: `/tmp/ripdpi-advanced-ime-device/`.
- Local XML results and preserved lint reports: that directory's `local-gates/`.
- Baseline pan: `20261016T164358Z/`. Rejected Compose-only candidate: `20261016T165739Z/`.
- Original Maestro RED: `20261016T173526Z/`. Save/persistence/restore GREEN: `20261016T174420Z/`.
- Permission interruption: `20261016T180855Z/`. Final API35 startup/cleanup: `api35-startup-20261016T181539Z/`.
- API30 blocked starts: `api30-startup-20261016T181900Z/` and `api30-startup-20261016T182021Z/`.
- Build and check logs: `/tmp/advanced-ime-final-assemble.log`,
  `/tmp/advanced-ime-supported-{focused-final,simple-final,static,goldens}.log`.

No physical-device, live network, relay-connectivity or full instrumentation-suite
acceptance is claimed by this report.
