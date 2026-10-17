# Compact profile catalog and useful empty history

Task: UIX-1792213600805378. Source commit: `8b8849ba7c2885f7bcfe910dbfb9c00914b2a0dc`.

## Result and scope

The profile catalog now starts with search and profile choice. Check options
hold the URL and bulk check. Profile details hold check-only and dates. Applied
state, measurements, failures, Cancel and cleanup recovery remain visible.
Applied profiles sort first within their section; the Recent filter keeps its
sequence order. Favorite and check callbacks retain their existing contracts.

History uses the source collection before filtering to choose its empty state.
Empty Connections and Events open Connection. Empty Diagnostics opens Diagnose
without a scan. These explicit actions do not restore saved child stacks or old
scan arguments. A filtered-empty result keeps search and filters and offers
Clear filters. Genuine empty sections omit the unused search/filter card.

All eight affected text keys are present in all ten shipped locales. No service,
network, persistence, wire, schema or native production code changed.

## Observed local evidence

| Check | Observed result |
|---|---|
| Initial behavior RED | 17 tests, 8 assertion failures: 5 catalog and 3 history; no errors or skips |
| Navigation RED with real NavController | 3 tests, 3 assertion failures for old automatic-scan arguments and saved Logs/ModeEditor stacks |
| Focused initial GREEN | 29 tests, no failures, errors or skips |
| Expanded Full checks | 146 tests, no failures, errors or skips |
| Expanded Simple checks | 146 tests, no failures, errors or skips |
| Screenshot comparison | 15 tests: populated history unchanged; 13 catalog PNG and 1 empty replay PNG differ as expected |
| Screenshot review | All 14 actual/compare pairs inspected by the integration owner and independent reviewer; no clipping or lost critical state found |
| Approved golden update | Full record/verify and Simple verify each passed all 14 tests, no failures, errors or skips; final files match approved actual bytes; other 406 PNG unchanged |
| Translation export | 5398 unique keys; owning export check passed |
| Design contract | DESIGN.md validator: 0 violations |
| Architecture health | 0 new, worsened or stale indicators; 116 crates and 354 internal edges |
| Cargo metadata | Locked full dependency metadata passed (116 workspace members) |
| Whole-tree static analysis and app/service locale lint | PASS; 741 tasks, 3m 55s |
| Source review | No open findings; the saved-navigation warning was fixed and regression-tested |

The expanded checks cover catalog projection and ViewModel behavior, history
projection and routes, actual Navigation Compose back stacks, theme contracts,
and full action labels at font scale 2.0 in LTR and Arabic RTL. Native screenshot
rendering tests are included explicitly; the default screenshot-package exclusion
does not apply to this run.

Raw evidence is under `/tmp/ripdpi-catalog-history-device/local-gates/`:
`red`, `navigation-red`, `green-focused-v2`, `targeted-both-v2`,
`targeted-both-v3`, `golden-compare` and `golden-compare-images`.
The expanded suite log is `/tmp/catalog-history-targeted-both-v3.log`.
The screenshot comparison log is `/tmp/catalog-history-golden-compare-v2.log`.

Earlier attempts with unavailable dependency cache or network routes did not run
behavior checks. One broad multi-task test command was cancelled because Gradle
applied its filters only to the last task. Its stale XML snapshot is labelled
`cancelled-multi-task/NOT-VALID-EVIDENCE.txt` and is not used for test counts.
The separate per-variant runs above passed and retain their own XML evidence.

## Real APK artifacts

`assembleGithubFullDebug assembleGithubSimpleDebug` passed in 9m 34s with the
real native build enabled, ARM64 and native CPU budget 4. Both APKs contain 13
ARM64 ELF entries. The source was clean in every production path before the build
and remains `8b8849ba7c2885f7bcfe910dbfb9c00914b2a0dc` for device acceptance.

| Variant | Version / code | SHA256 |
|---|---|---|
| GithubFullDebug | 0.1.4 / 20000012 | `c603114328195067cca77804aaae982c210c59ff11ec5633043f9223ce07e802` |
| GithubSimpleDebug | 0.1.4-simple / 20000012 | `05695b923d77fb6939829a33f0cf988e7030a9777f6f6d3a873bf04e7c9b78a1` |

The immutable APKs and complete native-entry hashes are in
`/tmp/ripdpi-catalog-history-device/apks-8b8849ba7/manifest.json`.
The build log is `/tmp/catalog-history-real-apk-build.log`. No stub APK is used.

## Emulator execution notes

The harness revisions and failed attempts remain separate from product acceptance.
No skipped case counts as a pass. Each executed attempt restored display, font,
application locale and navigation settings with three stable samples and an empty
crash buffer.

| Revision / evidence directory | Observed outcome | Cause and next action |
|---|---|---|
| v3 / `20261017T055602Z` | 0 pass, 0 fail, 17 skipped | Locale read before package presence; stopped before installation |
| v4 / `20261017T055923Z` | 0 pass, 1 fail, 16 skipped | Requested locale guard failed; actual locale was not logged, so cause remains unknown |
| v5 / `20261017T060442Z` | 1 diagnostic launch pass | Actual en-US and Activity configuration confirmed; this was not product acceptance |
| v6 / `20261017T062009Z` | 1 pass, 1 fail, 15 skipped | Default selected Connections tab was correctly non-clickable; harness required an unnecessary click |
| v7 / `20261017T062821Z` | 10 pass, 1 fail, 6 skipped | Harness treated the horizontal tab row as a vertical viewport and rejected a fully visible CTA |

These paths are under `/tmp/ripdpi-catalog-history-device/`. The later harness
uses a numeric current user, exact application locale checks and a first-launch
warmup before the matrix. The warmup is not a product case. A selected tab can
be a no-op only when fresh XML proves its selected state, bounds and matching
section content. Vertical viewport checks exclude known horizontal tab rows.
The font-2 Diagnostics CTA in v7 had full bounds `[108,1562,972,1731]` and full
text bounds `[241,1592,840,1701]`; the failed clipping region was the unrelated
tab row `[0,485,1080,642]`. This was a harness defect, not an application fix.

The v8 visibility fallback passed host XML replay but was not run on a device.
Independent review required a guard against vertical swipes on a fitting surface.
The v9 guard passed three host checks with mocked ADB and independent review.
These host checks are not product acceptance.

Application data and test APKs remain installed in the disposable pool. Locale
and display settings are restored; the harness does not claim to restore prior
APK versions or all application data.

## Final emulator acceptance

Reviewed v9 passed all 17 cases: 0 failures and 0 skips in 744.49 seconds.
The command used an exclusive API37 ARM64 Google APIs Pixel 10 Pro XL pool lease
(`RIPDPI_Pool_1`, explicit serial `emulator-5554`). Four configurations each
covered the Simple catalog and all three empty History sections: en/light/font1,
en/dark/font1, en/light/font2 and ar/dark/font2 at 360dp width. Arabic text and
actual Activity `ldrtl` configuration were checked. Critical action labels were
complete and targets retained at least 48dp. Horizontal tabs can expose partial
neighbouring labels; the active tab and targeted controls remain reachable.

The final Diagnostics cycle used the existing, explicitly labelled
`TEST-SEED-UI-STATE` preset. The unmatched query showed a filtered-empty state.
The empty-state Clear filters button was reached at `[108,1632,972,1776]` and
restored the same `history-diagnostics-automation-report-demo-session` row.
Connections and Events opened Config. Diagnostics opened its idle screen.
No profile check, service-start or scan-start control was tapped.

The device specialist inspected all 59 saved PNG. The independent reviewer
inspected 14 representative PNG, route XML, filtered-cycle evidence, settings
restoration and artifact identity. No target clipping, lost label or route defect
was found. All 59 PNG have XML and bounds; 63 capture events passed stable-pair
checks. The shared `actual-config` filename is overwritten between Connections
and Events, so its final XML/PNG represents Events. Separate tap and case results
retain both navigation checks.

All seven restoration mutations succeeded in 2.909 seconds. Three stable samples
matched the exact pre-run display override, density, font, navigation, night mode
and both app locales. The crash log was 0 bytes; neither app had a final
ServiceRecord. The pool command exited 0 and released its lease. No device was
removed. Final evidence:
`/tmp/ripdpi-catalog-history-device/20261017T070158Z/visual-review.md`,
`visual-review.json`, `metadata.json`, `results.json` and
`restoration-samples.json`.

Helper v9 SHA256:
`be0b5ace25216a81a11d241d2ef525ff3fc70db5c81e163af869d6889290a4ee`.
Wrapper SHA256:
`c668dc424ef5b1d564f5dd8d56e0b346ec62c6a002d8a9eec94119d386ecaac6`.

The accepted APK source is exactly `8b8849ba7c2885f7bcfe910dbfb9c00914b2a0dc`.
The integrated golden commit `e82cf62f940a427bfe1d6b289440e71554799704`
changes only the 14 approved test PNG; all production and build inputs are
identical. Fetch/rebase found no upstream change. Final combined checks passed on that combined branch: 146 Full tests, 146 Simple
tests, no failures/errors/skips, whole-tree staticAnalysis and explicit Full,
Simple and service lint (757 tasks, 2m 33s), design validation, task contracts,
architecture health and locked full Cargo metadata. Raw results are under
`local-gates/combined-final`; static log is
`/tmp/catalog-history-final-static-lint.log`. No baseline was extended.

## Device contract and limits

The reviewed harness uses only navigation, disclosure, search and filter actions.
It does not tap profile check, check-and-select, bulk check, service-start or
scan-start controls.
It verifies fresh XML/PNG pairs, compact width, both themes, font scale 2.0 and
real Arabic RTL. Its normal cleanup budget is separate from bounded interrupted
cleanup, and an interrupted run cannot report PASS.

The Simple APK uses the repository CI structural relay bundle. This can prove
real startup and UI presentation; it cannot prove relay connectivity. There is
no safe existing debug preset for active profile Checking, failure or cleanup
states. Those states use behavior tests and reviewed screenshots. Only Diagnostics
has an existing populated-history UI preset for a real filtered-empty device
cycle; Connections and Events use behavior tests for that filtered-empty case.

## Main integration and hosted CI

The source and approved golden commits were integrated with ff-only into main
at `e82cf62f940a427bfe1d6b289440e71554799704` and pushed to `origin/main`.
The main checkout was clean before integration. GitHub accepted the explicitly
requested direct push through its existing branch-rule bypass; required CI and
CodeQL were pending at push. No branch-protection setting was changed.

The initial hosted checkpoint observed Secret Scan and both locale workflows
passing. [Source CI run 39013413945](https://github.com/po4yka/RIPDPI/actions/runs/39013413945)
was initially queued on exact source
`e82cf62f940a427bfe1d6b289440e71554799704`. It completed successfully on
attempt 2 after the dependency retry described below. The ci-required gate
passed. CodeQL, harness-checks, Secret Scan, both locale workflows and
fleet-fixtures also completed successfully on that exact source.

### Initial hosted dependency failure

The first CI attempt failed in `xray-linked-unit-tests`, job `117341756739`.
Engine tests passed (329 tests). Service tests reported 1912 tests and 54
failures, with no errors or skips. All failures occurred during Robolectric
sandbox initialization: MavenArtifactFetcher received HTTP 403 from
`repo1.maven.org` while fetching an Android sandbox SDK POM or its SHA512 file.
No corresponding test body ran. There is no core, build-logic, Gradle or workflow
diff between prior main `79e71d147` and source `e82cf62f`.

The same POM returned 200 in a later local HEAD request. This does not prove
access from a GitHub runner. A request to rerun the specific failed job was
rejected while the overall workflow was still in progress; the attempt number
remained 1 and no tests reran at that point. The initial workflow then finished
with only this job and the dependent ci-required gate failing. All Debug/Release
variants and all five instrumented API27/33/35/36/37 lanes passed. A single
failed-job retry was accepted as attempt 2 on the unchanged exact source
`e82cf62f940a427bfe1d6b289440e71554799704`. The retry job
`117348199871` passed, then ci-required passed and the overall workflow
completed successfully. No source or gate changed between attempts.
Raw failure evidence:
`/tmp/catalog-ci-xray-job.log` and `/tmp/catalog-ci-xray-tests.zip`.
