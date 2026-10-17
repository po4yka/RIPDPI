---
task_id: UIX-1792228010381450
change: ui-action-clarity
commit_sha: 203ab331e11a898b2ecb42dfde8270f691f2f4f8
local: passed
local_evidence: The rebased app source passed 91 History and theme tests, staticAnalysis, Full and Simple lint, and service lint. The final Python helper passed eight host guards, Python compile, independent review, and structured review. Architecture health and locked Cargo metadata passed.
remote_ci: required
remote_ci_evidence: Baseline 9f11a4345 passed CI run 39027050440, including ci-required, all five Android device jobs, and three release shards. The follow-up is not yet published.
device: passed
device_evidence: The final helper passed all 15 Appium cases with zero failures, errors, or skips on API 37 arm64. Five independent Arabic dark font2 runs, an English light font1 run, and legacy compatibility checks used the clean APK with its installed hash verified. Crash and ANR scans were empty, settings were restored, and the pool lease was released.
artifact: passed
artifact_evidence: Clean app source 9b1f97129 produced APK SHA-256 92687eadde1e157c721141914daaedf5ad0f2c71a12122967dd6de76e84e2e3e. Generated BuildConfig and packaged DEX contain stamp 9b1f97129. The later helper-only commit leaves all app, core, native, and build inputs unchanged.
deployment: not_applicable
deployment_evidence: No deployment is owned by this Android UI change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIC-HOME | UIX-1792228194011328 | Direct callback, priority, disclosure and no-duplicate tests; instrumented Home check | passed |
| REQ-UIC-PERSONA | UIX-1792228194740670 | Open, cancel, current and different selection tests; device Back and plain restart persistence | passed |
| REQ-UIC-FILTERS | UIX-1792235542212033 | Exact scoped gestures, stable tab targeting, selected-tab and body acknowledgement; eight host guards and the final 15-case device matrix | passed |
| REQ-UIC-NEW | UIX-1792228196083085 | Identity/timestamp projection, count, filters, backfill, interrupted jump and reader tests; normal and font2 RTL device checks | passed |
| REQ-UIC-ACCESS | UIX-1792235542872043 | Final normal English and compact maximum-font Arabic RTL Appium checks passed; screenshots were inspected | passed |

## Planned validation

Run focused app unit tests for Home, persona, history filters, event counters,
and their UI projections, then all theme tests. Run Full and service lint,
`staticAnalysis`, translation-export discovery and manifest checks, architecture
health, locked Cargo metadata, and strict task validation. The user approved only
the 14 reviewed Home/filter PNG updates, with an isolated golden-blesser writer.

Build real Full debug and AndroidTest APKs. Record source SHA and artifact hashes.
Under an exclusive API37 arm64 pool lease, test Home recovery/disclosure, persona
choice and Back, compact filters and reset, new-event counts and retained reader
position. Inspect light, dark, font scale 2.0 and Arabic RTL. Keep local, hosted
CI, device, and artifact proof separate. Record any unavailable device evidence.

## Observed validation for the initial UI wave

The initial regression checks failed before their production fixes. Additional
boundary checks exposed incorrect permission routing, an empty filter disclosure,
an interrupted jump clearing unread events, and a 42 px reader shift when the
count label wrapped. All four checks passed after the fixes. The combined unit
run passed 465 tests with zero failures, errors or skips. It passed again after
the branch was synchronized with origin/main.

Full and Simple lint, service lint and staticAnalysis passed in Gradle offline
mode. The earlier online run was cancelled while the version detector waited for
Google Maven; that run is not recorded as a passed gate. Architecture health has
21 existing indicators, zero new or worsened entries, and zero stale entries.
Locked Cargo metadata, translation export parity and task contracts passed.

The user approved only 14 reviewed Home/filter PNG updates. A golden-blesser
recorded those seven methods in a separate worktree and checked every image
against the reviewed compare output. Root golden verification then passed all
19 captures, including five unchanged controls. No lint, detekt or architecture baseline was extended.

The APK source is 36b04215fa9eb60f125a1a66322a55b33e96e196. Subsequent golden
and evidence commits do not change app code. Native inputs came from the earlier
validated build; their source directories match this UI tree. Device acceptance
covers API 37 arm64. Other ABI and hosted device gates remain separate.

| Artifact | SHA-256 |
|---|---|
| app-github-full-arm64-v8a-debug.apk | 20b1aa933cf5847cafe843cdda5939bfbce85ed7d75acc0c19456e990a969e0c |
| app-github-full-debug-androidTest.apk | 61ab5d1f960111ea9fd3743ee2f1e4ed32fb4faac531bdfd602b5292a5be5830 |

The exclusive RIPDPI_Pool_1 lease used emulator-5554. All five instrumented
methods passed with zero failures, errors or skips. They mount production UI
components with controlled inputs, including raw event arrivals and three active
filters. Manual MainActivity checks confirmed direct Home recovery, Home lower
card access at font2 in Arabic RTL, persona Back, explicit Advanced selection,
and persistence after a plain restart without automation reset arguments.

Evidence is stored under
`/Users/npochaev/.codex/visualizations/2026/10/17/01a148c8-a09d-7332-a66f-75ad83a10c88/ripdpi-ui-action-clarity/`.
The directory includes rebased-combined unit XML and log, approved-golden-verify
results, the reviewed fixture manifest, APK hashes, and device screenshots and
instrumentation status records.

## Initial Appium replay limitation

The device preset used for the first native-event replay had no source or
severity choices. It could not prove a populated event-filter flow. One bounded
offline report attempt used diagnostics_report_demo, but the next search lookup
timed out. Its XML still contains History and the report row, with no detail-sheet
tag. This replay is not a passed acceptance result, and no full Appium suite is claimed. The extra trial
of chip-scroll helper changes was removed. Its failure artifacts remain in the
device evidence directory.

The five passing instrumented methods cover all four requested UI changes,
including the real FilterCard with three active restrictions, removal callbacks,
font2 RTL layout, and EventsSection raw-arrival counts with reader position.
Manual MainActivity coverage confirms Home action access and persona persistence.
The final crash buffer was empty and the captured log scan found no app crash or
ANR. Font scale was restored to 1.0 and the pool lease was released.

## Initial integration and hosted checks

The job branch was rebased against origin/main, and combined local gates passed.
The clean main checkout was advanced with a fast-forward merge. The authorized
push published 87608f918ee211723d365868c5fe1399f084c94e, and ls-remote returned
the same SHA. GitHub reported use of the account's existing bypass for PR and
required-check rules on this direct push. No repository rules were changed.

For that published code tree, translation export, locale parity, Secret Scan,
harness checks and CodeQL completed successfully. CI was queued and fleet-fixtures
was in progress when inspected. CI run:
https://github.com/po4yka/RIPDPI/actions/runs/39026907574.
At that publication the task remained in review pending hosted acceptance; it
was not archived. The follow-up below records the later baseline CI result.
This final evidence-only update is published after the code push above.

## Appium follow-up evidence

The user requested closure of the Appium gap. The earlier interpretation of the
failure PNG as a report detail sheet is withdrawn: the saved XML still contains
the History root and report row and does not contain a detail-sheet tag. A fresh
trace captured exact targets, bounds and scroll containers before the fix.
The existing offline diagnostics_report_demo provides real raw-path and completed
choices; no new fixture preset or external probe is needed.

Follow-up execution: UIX-1792235542212033 owns the reproducible navigation fix;
UIX-1792235542872043 owns populated-report emulator acceptance;
UIX-1792235543599801 owns exact-SHA CI inspection and publication. The evidence
above is the prior baseline. Local, artifact, and final device checks have passed. Publication remains required.

The corrected baseline test failed after the exact raw-path selection. Its reverse
swipe started at y=576, inside the horizontal tabs, above the vertical body at
y=742. The app stayed on History. Named list tags now scope all filter gestures.
Exact category requests fail if their requested key is absent; they do not select
another key. Legacy callers that need any available option state that intent.

The first scoped candidate exposed a second issue. Four mobile scrollGesture
calls returned false without moving the body. The same body accepted W3C touch
swipes in both directions. The helper now uses those bounded gestures, retains
exact identity and visible-bounds checks, and re-finds the target before a click.
The strict positive lifecycle and missing-category case then passed on the compact
Arabic dark screen at font scale 2.0. Their assertions and fixture stayed intact.

Static analysis also rejected the expanded modifier chain at 83 lines against
the EventsSection limit of 80. A local modifier value preserves its order and
layout and reduces the method length. No baseline or suppression was added.
The next combined run passed 91 unit tests without failures or skips, plus
staticAnalysis, Full and Simple lint, and service lint. Independent and structured
reviews found no remaining actionable issue.

Follow-up evidence root:
`/Users/npochaev/.codex/visualizations/2026/10/17/01a148c8-a09d-7332-a66f-75ad83a10c88/ripdpi-appium-filter-acceptance/`.
The final repeat matrix and clean committed-APK checks passed as recorded below.

The clean committed APK contains stamp 9b1f97129 and has SHA-256
92687eadde1e157c721141914daaedf5ad0f2c71a12122967dd6de76e84e2e3e.
Its first compact run passed the missing-category case but failed the positive
case after Connections to Diagnostics: Connections stayed selected, so the
Diagnostics body did not appear. This result reopened navigation acceptance before the acknowledgement fix below.
The earlier stability matrix passed 15 executions with no skips. It does not
replace the failed clean-APK check. Section selection needed a verified transition before the next control action.

The final helper-only fix waits for two matching observations of the exact tab
bounds, makes one click, then confirms both the selected tab and the displayed
section body. It updates the cached section only after that acknowledgement.
It adds no click retry or fixed sleep and leaves the strict test unchanged.
The moving-tab and delayed-body host regression failed before this fix and passed
after it; all eight host guards pass. Both reviews found no remaining actionable
issue. A fresh complete matrix with helper commit 203ab331e and the clean 9b1f97129
APK passed all 15 cases without failures, errors, or skips. This result is separate
from the earlier matrix and from the failed clean-APK cohort.

## Final Appium acceptance

Evidence directory: `final-matrix-203ab331e-clean9b` under the follow-up root.
Seven independent pytest processes passed: five compact Arabic dark font2 runs
with two strict cases each, two strict English light font1 cases, and three
legacy compatibility cases. Root independently parsed all seven JUnit files.
No failed process was retried or replaced within this matrix. The strict test
and its populated offline fixture stayed unchanged through the final helper fix.

The leased device was RIPDPI_Pool_1, emulator-5554, Android 17/API 37, arm64-v8a.
Each test process used a 1080x1920 viewport. The installed APK hash matches
92687eadde1e157c721141914daaedf5ad0f2c71a12122967dd6de76e84e2e3e.
The application source and embedded stamp are 9b1f97129; the helper source is
203ab331e. Their application, core, native, and build inputs are identical.

All seven crash buffers were empty; the captured app crash and ANR scan found
no match. Arabic and English screenshots retained the saved report and showed
no unexpected overlay or visible control overlap. Each process restored the
observed original font, app locale, night mode, size, and density. The pool
wrapper exited successfully and released its lease. Aggregate, runtime,
restoration, artifact, and visual review receipts are saved in that directory.

This is focused filter acceptance. It does not claim a full Appium suite run.
The latest application gates passed 91 unit tests with zero failures or skips,
staticAnalysis, Full and Simple lint, service lint, and the debug APK build.
The final helper passed all eight host regression checks and Python compile.
Architecture health, full locked Cargo metadata, task contracts, and both
independent and structured reviews also passed. No baseline was extended.
