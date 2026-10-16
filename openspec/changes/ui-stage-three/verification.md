---
task_id: UIX-1792145277214284
change: ui-stage-three
commit_sha: 91f14715276ba6833ab9cf34be73aa195cdc6688
local: passed
local_evidence: "5567 app unit tests, 578 executed screenshot tests, staticAnalysis, Full/Simple and service lint, locale export and architecture checks passed. See the evidence below."
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: "All 16 mandatory API37/API30 groups passed on source 91f147152. Fresh combined-source acceptance is required after upstream integration."
artifact: passed
artifact_evidence: "Real native Full, Simple and Full AndroidTest assembly passed. build/reports/ui-stage-three/apk-manifest-91f147152.json records source, metadata, native entries and SHA-256 values."
deployment: not_applicable
deployment_evidence: This presentation change owns no deployment.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UI3-LOCALES | UIX-1792145489182075 | Regenerated translation manifest and exact checker; hosted initial export repair | Passed |
| REQ-UI3-SETTINGS | UIX-1792145489882769 | Four ordered groups, actual values, preserved repairs and large-font reachability | Local and API37 passed |
| REQ-UI3-SIMPLE | UIX-1792145490486250 | Actual saved values and before/after/cancel/failure tests | Passed |
| REQ-UI3-BACKUP | UIX-1792145490486250 | Simple import/export access, reset hidden, real toolbar Back and consumed safe insets | Local, API37 and API30 passed |
| REQ-UI3-NAVIGATION | UIX-1792145491122016 | Full labels, roles, focus, callbacks and 48dp targets; real Arabic 98dp | Passed |
| REQ-UI3-NEUTRAL | UIX-1792145491122016 | Information symbol for neutral status; warning and quality regressions | Passed |
| REQ-UI3-LOCALES | UIX-1792145491791288 | Ten locales, 5393 synchronized keys, lint and approved screenshot verification | Passed |
| REQ-UI3-ACCESS | UIX-1792145492535364 | Combined checks, exact APKs, device matrix, independent review and final CI | Pre-rebase device matrix passed; combined-source and final CI remain open |

## Source and artifacts

The pre-rebase production source is `91f14715276ba6833ab9cf34be73aa195cdc6688`.
Upstream `170d69ce08ff6312d27b452f147cf5e8e9807172` appeared after these
checks. Its explicit Config/ModeEditor labels and both label-layout tests must
be preserved. The following results establish the pre-rebase source only.
Only six design, task and evidence files were dirty during its final checks.
`build/reports/ui-stage-three/post-device-fixes-source-v1.txt` records that
boundary. Real native assembly passed for Full, Simple and Full AndroidTest.
The two installed arm64 APKs are recorded in
`build/reports/ui-stage-three/apk-manifest-91f147152.json`:

- Full: `0b44c1252179c4475764193d07fda5eded3c700dd4354c2cd09c77a00ae8fa68`.
- Simple: `73c04f60ecd0d9200e5c2ee80159cf55e74f20582c73ca34fb32abe8fb38cd29`.

The manifest also lists native libraries and the owning Android output
metadata. The ignored Simple relay asset is byte-identical to the committed
standard secretless CI fixture. This establishes structural UI evidence only.

## Local checks

| Check | Observed result | Evidence |
|---|---|---|
| Full app unit suite | 2692 passed; zero failures, errors or skips | `build/reports/ui-stage-three/all-unit-v3/Full/` |
| Simple app unit suite | 2875 passed; zero failures, errors or skips | `build/reports/ui-stage-three/all-unit-v3/Simple/` |
| Targeted combined regressions | 62 Full and 40 Simple passed | `build/reports/ui-stage-three/device-fixes-green-v1/` |
| Full Roborazzi CI pattern | 297 executed tests passed; one existing experimental skip | `build/reports/ui-stage-three/final-verify/Full/` |
| Simple Roborazzi CI pattern | 281 executed tests passed; 29 existing variant/experimental skips | `build/reports/ui-stage-three/final-verify/Simple/` |
| Static analysis and lint | `staticAnalysis`, both app variants and service lint passed after the source commit | `/tmp/ripdpi-ui-stage-three-post-device-fixes-static-v1.log` |
| Translation export | 5393 keys; owning checker in sync | `scripts/ci/check-translation-export.sh` |
| Design source | Zero violations | `python3 scripts/ci/verify_design_md.py` |
| Architecture and Cargo | 21 existing indicators, zero new/worsened/stale; 116 locked workspace crates | `/tmp/ui-stage-three-91f-architecture.log`, `/tmp/ui-stage-three-integration-cargo.json` |

The screenshot commands used the complete CI pattern
`com.poyka.ripdpi.ui.screenshot.*` in separate Full and Simple invocations.
The skips were not added or used as positive evidence. They are the existing
unblessed `RtlVariantGalleryTest.rtlHandshakeTimeline` in both variants, plus
28 Full-only cases guarded in Simple. All 37 approved fixture PNG are covered
by executed tests. No source or screenshot changed during final comparison.

## Regression and review evidence

- Initial RED reproduced the old 160dp English navigation bar, missing selected
  language/applied DNS, missing no-value chevron and neutral warning symbol.
- Source-493 device captures then found two real follow-up defects: Arabic
  navigation was 114dp against the normative 112dp budget, and Simple backup
  Back overlapped the system status bar.
- The new real-Arabic JVM test also failed at exactly 114dp before the padding
  change. Its record is `build/reports/ui-stage-three/device-repair-red/arabic.xml`.
- The overlay RED failed normal and maximum-font cases: top=0px instead of 52px.
  The already-consumed parent inset case passed. `simple-insets.xml` is stored
  beside the Arabic record. All three cases passed after the single safe-area
  modifier was added; they also invoke the actual toolbar Back callback.
- Independent review of the implementation and each follow-up delta found no
  actionable issue. Reviewers did not modify files or claim device acceptance.
- No lint, detekt, architecture, security or performance limit/baseline changed.

## Fixture authorization

The user explicitly approved two reviewed groups on 2026-10-16:

- 15 PNG: all nine Simple home images, paired ModeEditor saving, and four catalog
  mode-editor/onboarding images. Specialist commit `fd1204af3`, integrated as
  `7336aac4c`.
- 22 PNG: English/Spanish/French large-font navigation, two maximum-font shells,
  five Settings/DNS catalog images, three locale Settings images, and six
  Russian theme previews. Specialist commit `912861189`, integrated as
  `ef08054eb`.

The isolated golden specialist inspected all actual/diff pairs, recorded only
those owning methods, inspected all recorded images, and verified both groups.
The six theme previews use the corrected 1600dp component-test viewport and
show the complete Interface card. Production Settings remains a scrolling list.
Final source-91f comparison passed without another fixture change. Other
fixture families stayed fixed.

## Device evidence

All device operations use a command-scoped pool lease and an explicit serial.
The specialist verifies source and APK digests before installation and retains
fresh XML, PNG, raw inset geometry and launch transcripts.

The source-493 attempts remain failed evidence under
`/tmp/ripdpi-ui-stage-three-device/20261016T115052Z`. The 114dp exception was
withdrawn; the normative limit was preserved. The harness was corrected for
fixed navigation outside a scrolling viewport and scroll progress through
untagged guidance text. Those corrections do not replace the product fixes.

Source-91f API37 evidence is under
`/tmp/ripdpi-ui-stage-three-device/20261016T123250Z`:

- All 12 mandatory groups passed: eight gesture-mode groups and four targeted
  three-button groups. They cover four Settings groups, native Russian/current
  icon values, permission repair, LTR/RTL navigation and Simple backup at normal
  and maximum font in light/dark themes.
- English navigation is 96dp; Arabic is 98dp. Every destination remains present,
  selected state follows real taps, and targets retain at least 48dp.
- Simple import/export is reachable, reset is absent, and an actual toolbar Back
  tap returns Home. Back is `[60,168][204,312]`, below the status-bar frame
  `[0,0][1080,156]`; the old overlap is gone.
- The overall v5 run retains an optional IME harness failure: a stale client flag
  contradicted server state and the captured hidden keyboard. A separate retry
  passed using server visibility and fresh visual/geometry evidence. The encrypted
  backup IME check is blocked by the real BIOMETRIC_STRONG guard.

API30 passed all four Simple backup groups in light/dark at font2 with gesture
and three-button navigation. Back is `[60,84][204,228]`, below its 72px status
bar. The temporary AVD was removed by its owning pool wrapper. All 108 fresh
PNG/XML pairs were validated and visually inspected; crash buffers were empty.
The optional Advanced IME check passed. Its retry retained a runner failure
from an early navigation-mode restoration read. A separate recovery lease
observed the original gestural mode2 in four stable samples without mutation.
Both pool slots are idle; dimensions, density, font, per-app locales and
navigation mode are restored and test services are stopped.

The final report and machine-readable record are
`/tmp/ripdpi-ui-stage-three-device/final-report.md` and `final-summary.json`.
Automation presets reset disposable app data; no full DataStore/Room snapshot
restore is claimed. The optional Full Advanced form toolbar pans partly under
the status bar while IME is open and returns after Back. This path is outside
the Simple overlay change. No base-runtime comparison establishes its age or
cause; it remains a separate observation.

## CI and integration

The initial export repair was pushed at `335d5de36` and passed:
https://github.com/po4yka/RIPDPI/actions/runs/38886720300 . The exact local RED
had 28 missing stage-two keys; the owning generator fixed the manifest.

The overall main CI at that revision exposed two unit defects and stale setup
fixtures. The direct Config runtime collector now uses the existing RESUMED
lifecycle helper; its guard test remains unchanged. Config mode layout tests
now use the actual scope resources and preserve their overflow, selection and
callback assertions.

Intermediate hosted run https://github.com/po4yka/RIPDPI/actions/runs/38891223701
checked `fc7797e21`. Kotlin coverage, Android test assembly and all five
instrumented API lanes (27/33/35/36/37) passed. That run still failed static
analysis and screenshots. The long backup functions were split with unchanged
state/callbacks and no raised limit; approved fixtures were then integrated.
This intermediate result does not prove final main CI. Final main integration,
push and hosted verification are still required.

## Acceptance limits

Simple network connectivity, actual file export/import/reset, manual TalkBack
speech, SwitchAccess scanning and physical-device frame performance were not
measured. No app network probe or client connection was requested; background
egress was not measured. Exact automatic Undo remains outside this change.
Existing backup payload and remembered-network boundaries remain in force.
Do not archive while a required evidence category is open.
