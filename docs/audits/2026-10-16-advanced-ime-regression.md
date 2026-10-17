# Advanced keyboard regression verification

Date: 2026-10-16. Application source: `3945363270c62a284feea927569f0f69e5b24f4d`.
API35/API37, edit/save and the final API30 focus recheck passed. The implementation
was integrated with ff-only at `85da04a2b` and pushed through main
`79e71d1473fc320e55ac2bb9aba936b10a4f199d`. Hosted CI 38950288714 completed
successfully on that exact main commit. The final API30 gate was completed on
2026-10-17; its evidence is separate from later catalog/history UI acceptance.

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
| 30 | Same two Advanced cases | PASS: 2 passed, 0 failed, 0 skipped | `20261017T052700Z` |

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

## Retained preparation failures

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
The user then explicitly authorized removal of idle `RIPDPI_Pool_2`. Documented
dry-run and cleanup removed only that device. After both approved removals, the
next API30 start still failed before boot: 8,627.29 MB available, 12,288 MB
required. The wrapper removed its own temporary AVD and retained startup logs.
No disk gate or userdata size was changed. API30 was still open at that
checkpoint. The final recheck below closes this verification gap; unrelated
device data was not removed.

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
- API30 blocked starts: `api30-startup-20261016T181900Z/`, `api30-startup-20261016T182021Z/`
  and the post-Pool2 retry `api30-startup-20261016T184718Z/`.
- Build and check logs: `/tmp/advanced-ime-final-assemble.log`,
  `/tmp/advanced-ime-supported-{focused-final,simple-final,static,goldens}.log`.

No physical-device, live network, relay-connectivity or full instrumentation-suite
acceptance is claimed by this report.

## Integration checks

The job branch was fetched and rebased against origin/main before integration;
it was already current. Production source matches the accepted APK source with
an empty diff over app production, core, build logic, native and Gradle inputs.
The combined tree passed task validation, design validation, architecture health
and locked Cargo metadata again. The prior Full/Simple, lint, static and unchanged
screenshot results apply to the identical application source. Independent final
review found no code blocker and required the explicit API30 limitation above.

## Final API30 focus recheck (2026-10-17)

The unchanged accepted Full APK passed both required cases on a non-rooted API30
ARM64 Google APIs Pixel 10 Pro XL emulator: dark/font2/three-button/direct entry
and light/font1/gestures/entry through Settings. Results: 2 passed, 0 failed,
0 skipped. All four shown/hidden PNG were inspected by the device specialist and
an independent reviewer. The focused field was at `[108,1017,972,1161]`, with
IME top 1161px. Back was at `[60,84,204,228]`, below the status bar ending at
72px, and retained its 48dp target. First Back hid IME and kept Advanced plus
value 14. The real toolbar Back returned to Home or Settings as required.

The run used source `3945363270c62a284feea927569f0f69e5b24f4d` and accepted APK
SHA256 `755f97512d8da7ca35dd478356b83324922a1828dfc9d18fe1a7437a2a736975`.
The observed checkout differed from the declared APK source only in task/docs
and the previously verified Maestro flow; the application production diff was
empty. Final focus helper v7 SHA256:
`a72d89fd036b76ceb891d57f54a359fec79e9922aa7a9ba8fd875f8084049f68`.
Earlier v5/v6 locale guards stopped before the cases and remain separate failed
preparation attempts; they do not count as acceptance.

Size 1344x2992, density 480 and the original font setting were restored. Navigation
restoration passed three stable samples. Crash buffer was empty and app services
were absent. The wrapper returned 0 and removed only its owner-created temporary
AVD; its `.avd`, `.ini` and metadata were confirmed absent. Raw evidence:
`/tmp/ripdpi-advanced-ime-device/20261017T052700Z/visual-review.md` and
`/tmp/ripdpi-advanced-ime-device/api30-startup-20261017T052640Z/wrapper-result.json`.
This focus recheck does not claim a full API30 Maestro/edit-save suite.

[Hosted main CI](https://github.com/po4yka/RIPDPI/actions/runs/38950288714)
was re-read on 2026-10-17: completed/success at exact main `79e71d147`.
