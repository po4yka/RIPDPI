---
task_id: UIX-1792228010381450
change: ui-action-clarity
commit_sha: 9f11a434572f52a2340a1e27ccac803db6f9e839
local: required
local_evidence: 465 focused Full unit tests passed with no failures or skips; Full and Simple lint, service lint, staticAnalysis, translation export, architecture health, locked Cargo metadata and task validation passed. Golden verify passed 10 tests and 19 captures after the approved updates.
remote_ci: required
remote_ci_evidence: Code push 87608f918 is confirmed on origin/main. CI run 39026907574 is queued; translation export, locale parity, Secret Scan, harness checks and CodeQL passed for that SHA. Hosted acceptance remains pending.
device: required
device_evidence: API 37 arm64 exclusive pool lease; five production-component instrumented methods passed with zero failures, errors or skips. Manual MainActivity Home and persona checks passed. Additional Appium filter replay did not complete; see the limitation below.
artifact: required
artifact_evidence: Real Full arm64 debug and AndroidTest APKs built from source 36b04215f; device harness verified both SHA-256 hashes before install.
deployment: not_applicable
deployment_evidence: No deployment is owned by this Android UI change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIC-HOME | UIX-1792228194011328 | Direct callback, priority, disclosure and no-duplicate tests; instrumented Home check | passed |
| REQ-UIC-PERSONA | UIX-1792228194740670 | Open, cancel, current and different selection tests; device Back and plain restart persistence | passed |
| REQ-UIC-FILTERS | UIX-1792235542212033 | Prior component checks passed; strict populated-report Appium regression and scoped scroll fix are required | pending |
| REQ-UIC-NEW | UIX-1792228196083085 | Identity/timestamp projection, count, filters, backfill, interrupted jump and reader tests; normal and font2 RTL device checks | passed |
| REQ-UIC-ACCESS | UIX-1792235542872043 | Prior local and instrumented checks passed; normal and maximum-font RTL Appium acceptance is required | pending |

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

## Observed validation

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

## Additional Appium replay limitation

The device preset used for the first native-event replay had no source or
severity choices. It could not prove a populated event-filter flow. One bounded
offline report attempt used diagnostics_report_demo, but navigation entered a
report detail sheet and the next search lookup timed out. This replay is not a
passed acceptance result, and no full Appium suite is claimed. The extra trial
of chip-scroll helper changes was removed. Its failure artifacts remain in the
device evidence directory.

The five passing instrumented methods cover all four requested UI changes,
including the real FilterCard with three active restrictions, removal callbacks,
font2 RTL layout, and EventsSection raw-arrival counts with reader position.
Manual MainActivity coverage confirms Home action access and persona persistence.
The final crash buffer was empty and the captured log scan found no app crash or
ANR. Font scale was restored to 1.0 and the pool lease was released.

## Integration and hosted checks

The job branch was rebased against origin/main, and combined local gates passed.
The clean main checkout was advanced with a fast-forward merge. The authorized
push published 87608f918ee211723d365868c5fe1399f084c94e, and ls-remote returned
the same SHA. GitHub reported use of the account's existing bypass for PR and
required-check rules on this direct push. No repository rules were changed.

For that published code tree, translation export, locale parity, Secret Scan,
harness checks and CodeQL completed successfully. CI was queued and fleet-fixtures
was in progress when inspected. CI run:
https://github.com/po4yka/RIPDPI/actions/runs/39026907574.
The task remains in review until hosted acceptance is observed; it is not archived.
This final evidence-only update is published after the code push above.

## Appium follow-up in progress

The user requested closure of the Appium gap. The earlier interpretation of the
failure PNG as a report detail sheet is withdrawn: the saved XML still contains
the History root and report row and does not contain a detail-sheet tag. A fresh
trace will capture exact targets, bounds and scroll containers before a fix.
The existing offline diagnostics_report_demo provides real raw-path and completed
choices; no new fixture preset or external probe is needed.

Follow-up execution: UIX-1792235542212033 owns the reproducible navigation fix;
UIX-1792235542872043 owns populated-report emulator acceptance;
UIX-1792235543599801 owns exact-SHA CI inspection and publication. The evidence
above is the prior baseline. Current local, device and artifact states remain
required until the follow-up tree is checked.
