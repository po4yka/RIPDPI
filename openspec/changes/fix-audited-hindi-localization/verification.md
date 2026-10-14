---
task_id: UIX-1791880509070975
change: fix-audited-hindi-localization
commit_sha: e4692c8b68df456194c378b12e5c83617e35a1aa
local: blocked
local_evidence: Final combined-tree checks passed 2483 app tests, 2244 service tests, 20 resource regressions, static analysis, lint, and 285 Full screenshots. One existing RTL gallery test is ignored. Simple screenshots are blocked before tests because the required real embedded relay bundle is absent.
remote_ci: required
remote_ci_evidence: Hosted checks for the final published SHA remain required.
device: not_applicable
device_evidence: Local rendered UI and resource checks own this presentation change. Physical-device acceptance is not claimed.
artifact: not_applicable
artifact_evidence: No signed APK or release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HI-MEANING | UIX-1791880749935501 | Hindi resource regressions and inspected UI | Passed |
| REQ-HI-RESULTS | UIX-1791880769991455 | Known-result, retained-language-switch, and option behavior tests | Passed |
| REQ-HI-DATES | UIX-1791880789607066 | Host-pack fallback and selected-locale language-switch tests | Passed |
| REQ-HI-PARITY | UIX-1791880815019998 | All-locale guards, full app/service tests, lint, static analysis, architecture, review, and screenshots | All listed gates passed; Simple screenshots blocked |

## Scope

- Correct 70 existing Hindi entries: 63 active and seven unused entries.
- Correct 42 stale profile-editor references in English, German, French,
  Persian, Arabic, and Brazilian Portuguese.
- Translate 46 descriptive option positions. Preserve 13 technical choices,
  all array positions, and all 15 stored-value arrays.
- Add 45 string keys in all ten locales. Localize known DPI, PT, TCP16,
  host-pack, duplicate, and sequential captions.
- Refresh known PT text and catalog dates when the selected app language
  changes. Preserve raw evidence, custom descriptions, and custom captions.
- Read numeric Android array values with typed resources; recycle the array.
  The stored values zero through three remain exact.

No protocol, schema, dependency, raw-evidence, or stored-value contract changed.
No quality baseline changed. Only the expressly authorized Arabic metric
screenshot fixture changed. Prior Russian, Chinese, and Spanish correction
commits remain ancestors; this change preserves their corrections.

Three disputed Hindi DNS terms (Authoritative/AA, Truncated/TC, and
Truncated response) remain unchanged. They are not confirmed defects.
Native-speaker sign-off and physical-device acceptance are not claimed.

## Source and independent review

The final source candidate is `e4692c8b68df456194c378b12e5c83617e35a1aa`.
The job branch ran `git fetch origin` and `git rebase origin/main` twice as
upstream advanced, finally through
`b63b0ffced6fbcd05d3b29f0b37a0f0a23fa121d`. Both rebases had no conflicts.
All 20 intervening upstream commits are preserved.

Independent review found no critical or warning findings. A completeness
pass found no missing confirmed session findings. After the second rebase,
all 68 task files matched their pre-rebase contents. The nine additional
upstream files were outside the task slice. `git diff --check` passed.

## Resource and repository gates

These checks passed again on the final source candidate:

| Command | Observed result |
|---|---|
| `python3 -m unittest discover -s scripts/ci -p 'test_*localization_regressions.py'` | 20 passed: Russian 4, Chinese 5, Spanish 5, Hindi 6 |
| `bash scripts/ci/check-locale-parity.sh` | All ten app, Quick Connect, and service locales passed; option references and order passed |
| `bash scripts/ci/check-translation-export.sh` | Manifest in sync: 4,958 strings; app has 4,948 strings and 23 plural entries |
| `python3 scripts/ci/check_architecture_health.py` | 21 baseline indicators; no new, worsened, or stale entries; 116 crates and 354 edges |
| `python3 scripts/ci/check_file_loc_limits.py` | 4,162 files; no violations or baseline growth |
| `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1` | Passed |
| `./taskctl validate` | 128 tasks and 361 execution steps passed |

Pre-commit formatting, workflow pins, secret/size checks, harness checks,
staged architecture delta, and Conventional Commit checks passed for the
source and fixture commits. The final evidence commit runs the same applicable hooks.

## Behavior tests and render inspection

The targeted suite passed 32 tests with no failures, errors, or skips. It
covers Hindi diagnostic captions, selected-locale catalog dates, host-pack
fallbacks, numeric array values, all-locale option resources, and retained PT
text after language changes.

Native Compose test renders were inspected at font scales 1.0 and 1.5.
Hindi host-pack descriptions, duplicate/sequential dropdowns, TCP16 captions,
DPI failures, and retained PT summaries/verdicts had readable glyphs and no
observed clipping. Disposable renders are under
`app/build/compose-previews/renders/`; they were not copied into fixtures.

Two defects appeared during test development. A bare Text test harness
failed the overflow assertion; the harness now renders the actual production
dropdown and retains the assertion. Android `getStringArray` returned null
for numeric options; the production reader now uses `TypedArray.getString`.
The full targeted rerun passed after both corrections.

## Android combined-tree gates

All compiler and Gradle commands use the machine-wide `build-gate`. The JVM
and lint commands use one worker, no daemon, `ripdpi.nativeCpuBudget=4`,
`ripdpi.skipNativeBuild=true`, and the existing real Xray AAR at
`/Users/npochaev/GitHub/RIPDPI/native/xray/artifacts`. No APK was assembled
with this native-less validation mode.

The final candidate ran these commands:

| Command | Final candidate result |
|---|---|
| `:app:testGithubFullDebugUnitTest` | 2,483 passed; zero failures, errors, or skips |
| `:core:service:testDebugUnitTest` | 2,244 passed; zero failures, errors, or skips |
| `staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug` | Passed; 750 tasks, 11m57s |
| `:app:verifyRoborazziGithubFullDebug -Pripdpi.includeRoborazziUnitTests=true --tests 'com.poyka.ripdpi.ui.screenshot.*'` | 285 passed; zero failures or errors; one existing ignored RTL gallery case |

Intermediate rebased source `6a755a28c3031ed21f63a5d7ce898356a4fdbe2d`
passed 2,483 app tests and 2,242 service tests with no failures, errors, or
skips. Static analysis and both lint commands passed. Full screenshots had
285 passes, zero failures or errors, and one existing ignored RTL gallery
case. The table above records the later final candidate results.

Logs and preserved test XML are under
`app/build/hi-localization-validation/`. Final candidate logs use the
`final-upstream-` prefix; the source SHA and exit results are in
`final-upstream-source-sha.txt` and `final-upstream-gate-results.json`.

## Authorized Arabic metric fixture

The user expressly approved only
`app/src/test/screenshots/com.poyka.ripdpi.ui.screenshot.DiagnosticsMeasurementsScreenshotTest.metric_meanings_rtl.png`.
An isolated golden-blesser recorded the one owning test, then verified all
eight tests in its class. Both runs passed with no skips. Expected, actual,
and comparison images were inspected. The integrated image is identical
to that reviewed output.

The earlier Spanish formatter correction `aa28e5aaa` selected the app
locale for numbers. Arabic TX/RX decimals therefore use Arabic digits. The
old fixture used Latin digits and differed by 8.005 percent. Both images
are 420 by 1,500 px; lower cards move by about 10 px with no observed
clipping. No other fixture changed and no threshold was relaxed.

`RtlVariantGalleryTest.rtlHandshakeTimeline` remains an existing `@Ignore`
because its gallery goldens are not blessed. It is not a flavor assumption
and is not counted as a pass. This separate gallery was not blessed or
unignored.

## Simple bundle blocker

`:app:verifyRoborazziGithubSimpleDebug` stopped before tests at
`verifyEmbeddedRelayBundle`: the required ignored asset
`app/src/simple/assets/embedded-relay-bundle.json` is absent. No Simple
screenshot result is claimed.

The owning CI action decodes the private `embedded-relay-bundle-base64`
input. Four existing local assets were found; all were byte-identical to
the committed placeholder CI fixture. None was a real bundle. All 13 published GitHub releases had no Simple
APK asset; five local APKs had no embedded bundle ZIP entry.

The user requested finding or generating the bundle. The real generator is
`make emit-bundle CLIENT=<existing-client>` in sibling `ripdpi-vpn-deploy`.
It requires deployed-server Terraform state and operator SOPS credentials.
The documented operator configuration directory is absent, the local
secrets directory contains only schemas/examples, and the provider folders
have no local state or Terraform environment selection. No usable source
subscription or real client profile was found in the scoped sources.

ConfigSeeder requires a VLESS+Reality primary, VLESS/xHTTP reserve,
Hysteria2 reserve, and runtime-ready AWG profile. Generating JSON cannot
create access to those servers. No placeholder, invented endpoint, skipped
gate, or fabricated credentials were used as evidence. No production
server was provisioned. The exact next check is the Simple command above
after a real bundle is available.

## Earlier failures and retries

The initial full app run had 628 failures among 2,483 cases: one existing
`DiagnosticsRouteTest` `FileSystemAlreadyExistsException`, followed by
451 `UnsatisfiedLinkError` and 176 `UncaughtExceptionsBeforeTest` failures.
A fresh standalone app run passed all 2,483 tests. No source change,
test exclusion, cache deletion, or threshold change was used for this retry.
The same Robolectric ZIP/native-renderer cascade was recorded in the
previous Chinese verification.

Two initial service runs had one failure each among 2,230 cases. The first
failed the UDP payload ladder assertion; the second failed the UDP question
entropy assertion. The first case passed in the second run. Real-socket
timing was a possible cause, not an established cause. No tests or timeouts
were changed by this patch. A full rebased service run then passed 2,242
cases. Upstream subsequently changed UDP probe binding and its tests; the
final candidate rerun includes those changes.

## Publication boundary

Main integration and push are expressly authorized. Hosted CI must be
checked against the final published SHA; local checks do not prove hosted
acceptance. The portfolio remains in review rather than closed until its
required evidence is resolved. The Simple blocker and existing ignored
RTL gallery remain explicit gaps.
