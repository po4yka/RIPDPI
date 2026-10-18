---
task_id: UIX-1792228984398359
change: compose-common-styles
commit_sha: 9793d98b6e15f9376e5e246cd15bdd3a016f0361
local: required
local_evidence: Earlier local checks are recorded below. The branch is now rebased on main e82b513ff; combined-tree checks are pending before integration.
remote_ci: required
remote_ci_evidence: The owner authorized main integration and remote push on 2026-10-18. Hosted checks remain pending publication.
device: required
device_evidence: Source 9793d98 passed six cases and both product launchers. The combined-tree device repeat is pending fresh APKs.
artifact: passed
artifact_evidence: The owner accepted the completed migration on 2026-10-18 after the six-run comparison returned INCONCLUSIVE. This is owner acceptance of the recorded limitation, not a numerical performance PASS. Fresh combined-tree APKs remain part of the local integration gate.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Owner acceptance and final integration

On 2026-10-18 the owner stated that acceptance was complete and authorized final main integration, documentation and dependency updates, and remote push. This decision accepts the recorded emulator performance limitation. The strict comparison remains INCONCLUSIVE, exit 3, with the Controls observations retained. Neither the five-percent threshold nor the raw evidence is changed.

The integration rebases the migration onto `e82b513ff8ad9da3f6163a52a71ac8337f1f1877`. It retains current haptic preference/result handling, diagnostics progress, route state and responsiveness changes. Both benchmark-only activities remain declared; a duplicate `benchmark` build-type declaration from the automatic merge is removed. Combined-tree build, test and device results will be recorded before main is advanced. Historical APK and performance source SHAs below remain their actual build identities.

## Hosted integration correction

Run [39127078764](https://github.com/po4yka/RIPDPI/actions/runs/39127078764) tested source `d804ca4cb1ef25e89e4f4beffcb25c7da7864e86`. Unit tests passed: Full 2,921 and Simple 3,104, with zero failures or skips. Both screenshot suites, static analysis, all three channel Debug builds, and all three channel release-verification shards passed. The required aggregate failed because the instrumented matrix failed. This run is not an integration PASS.

On API 27 the generic test Activity lacks the production host's `adjustResize` window mode. LatinIME starts and renders, but the test does not observe IME insets. The test now sets the same resize mode as MainActivity before it sets content; API 27 and its assertions remain unchanged. This diagnosis and correction require the next CI device run.

The API 33 ATD image has no software IME. Its log records `mSelectedMethodId is null!` after the field requests the keyboard. The CI lane and shared managed-device registry now select the full Google APIs image for API 33; API coverage and IME assertions remain unchanged. The separate API 34 ATD benchmark image is unchanged.

The shell test failed during Activity destruction: the legacy Compose test continuation interceptor sent snapshot notifications inside Styles animation cancellation, after the resolver was unbound. The shell class now uses the public JUnit v2 test environment and explicit default `ComposeUiTestConfig`, as the Styles interaction class already does. Product sources, motion and test assertions are unchanged. All 38 build-convention unit tests, 49 CI helper tests and workflow actionlint passed for the correction. The next required hosted run must verify the device corrections; no successful rerun is claimed here.

The owner declared acceptance complete. The redundant seven-phase local repeat was cancelled before admission, without child processes. It produced no fresh APK, test or device result. Historical local, device and performance records keep their original source identities; current integration checks run in hosted CI. Cancellation and downloaded hosted reports are preserved under [ripdpi-styles-integration](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-integration).

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RDS-STYLE-PARITY | UIX-1792229085066929 | Source 93bc75744; Full/Simple unit tests and owning screenshot verification; all 420 expected fixtures covered | Passed locally |
| REQ-RDS-STYLE-OVERRIDE | UIX-1792229082522189 | `styles-wave1-after-apply.log`: ten style tests plus existing theme/shared-control tests | Passed locally |
| REQ-RDS-STYLE-THEME | UIX-1792229083651465 | Theme, state, reduced-motion and control tests; complete Full/Simple unit and screenshot repeat at source 93bc75744 | Passed locally |
| REQ-RDS-STYLE-COMPATIBILITY | UIX-1792229081138948 | `styles-dependency-gate-final.log`: AAR metadata, Full/Simple compilation, build convention tests | Passed locally |
| REQ-RDS-STYLE-ACCEPTANCE | UIX-1792229086339829 | Current Full/Simple APK and device checks passed; six actual performance runs completed | Owner accepted the recorded INCONCLUSIVE limitation; combined-tree gate pending |

## Latest source validation

Source `9793d98b6e15f9376e5e246cd15bdd3a016f0361` was clean through APK assembly, device checks and performance measurement. Its runtime and build sources equal `93bc75744e567f3baa96fe9965bb0d6164d7fa70`; only five planning files differ. New evidence is stored outside disposable build directories in [ripdpi-styles-validation](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation). Earlier unit, screenshot and static-analysis results remain observed historical results. Their raw `build/reports/` files became unavailable during a separate host capacity change. They were not restored, recreated or rerun in this continuation.

| Current check | Observed result | Evidence |
|---|---|---|
| Full app and AndroidTest APK assembly | Passed; repository-owned ARM64 native and pluggable-transport source builds; verified Xray AAR | [APK summary](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/apks/run-9793d98-repaired/summary.json) |
| Simple app and AndroidTest APK assembly | Passed; normal bundle verifier and source builds | [Four-APK manifest](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/apks/run-9793d98-repaired/apk-manifest.json) |
| Expected screenshots | All 420 hashes unchanged during assembly | APK summary and artifact controller record |
| Current-source device repeat | Full 3/3 and Simple 3/3; zero failures, errors or skips | [Actual report](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/device/run-9793d98-resumed-fixed/report.json) |
| Product launcher checks | Both passed; actual manifest aliases resolve to MainActivity; crash buffers empty | Actual report, UI XML and PNG files in the same directory |
| Three-state performance comparison | Six complete runs; 24 benchmark cases, 240 iteration traces and six raw JSON files; INCONCLUSIVE, exit 3 | [Comparison](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/performance/measurements/three-state-20261018-listener-fix-1/comparison.md), [manifest](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/performance/measurements/three-state-20261018-listener-fix-1/manifest.json), [summary](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/performance/measurements/three-state-20261018-listener-fix-1/summary.json) |

The three device cases are `disconnectNeedsASecondActionBeforeItDispatches`, `dropdownSelectsOnceClosesAndKeepsDisabledBehavior`, and `textInputAndDoneActionKeepTheValueAndReleaseFocus`. Each ran on both variants under one standard pool lease. The runner checked saved APK hashes before installation and after the run, and checked product activity identity. Full English and Simple Arabic launcher PNGs were inspected; no obvious text overlap or clipping was found in these captures. This is a launcher and interaction check, not full navigation or live network acceptance. Font scale and IME were restored. Existing Simple app locales were restored; Full was absent before installation, so restoring an earlier Full locale was not applicable.

All three benchmark states use the same release-derived, non-debuggable, shell-profileable configuration and identical harness sources. No APK was rebuilt between series. One API 37 ARM64 pool lease held `emulator-5558` through all six runs. The second series used reverse state order. Actual installed APK hashes, context records, output paths and traces connect the source declarations to execution. Settings restoration and owned process cleanup passed; the shared build slot was released.

The strict parser found no metric with an original-to-Styles regression above 5 percent in both series. Each metric crossed the limit in only one series, so this is INCONCLUSIVE, not PASS. Controls were slower than the dependency-only state by 15.85 and 6.61 percent. `performance.md` retains all values and the evidence limits. The owner later accepted this limitation; the numerical comparison did not pass. Step `UIX-1792229086339829` stays open until combined-tree integration checks finish. No golden or performance baseline was changed.

### Storage recovery and build repairs

The scoped cleanup removed three closed ignored native-packaging directories in the inactive Appium worktree and the eligible idle `RIPDPI_Pool_2` through the normal pool cleanup command. The observed free-space gains total 3,992,829,952 bytes, about 3.72 GiB. Protected source, reports and fixture hashes remained unchanged. A later independent capacity increase and missing build trees are recorded separately; that increase is not attributed to this cleanup. See [recorded observations](/Users/npochaev/Documents/Codex/2026-10-18/ripdpi-styles-validation/resumed-observations.json).

The first source rebuild exposed missing files in two private Cargo registries. Archive checksums were checked against locked or exact registry metadata; only 90 missing files in each private home were restored. Existing file contents, shared caches, source and Cargo configuration were preserved. The following normal source builds passed. The Simple asset remains the exact repository CI fixture, SHA-256 `cadaff4fd5daa0cfeec5182e428a3207d926dd7ea62b917edadc21876a92194c`; it supplies local build data, not production relay credentials.

Earlier failed controller runs remain separate. They exposed launcher aliases, Activity reuse between preflight scenes, and a missing AndroidX run-end listener. The final controllers resolve aliases through the actual manifest, start each preflight scene separately, and pass the packaged AndroidX 1.5.0 listener to instrumentation. They still require exact test identities and reported output paths. No app APK, benchmark workload, comparison threshold or parser validity check was changed to accept those failures. The final complete runs are the evidence above.

## Earlier source validation record

This record describes checks observed before the current APK and device repeat. Its raw reports later became unavailable. Pending statements in this record are historical; the current result is above.

The historical gate used production and test source `93bc75744e567f3baa96fe9965bb0d6164d7fa70`. Later evidence-only commits do not extend runtime validation to new source.

| Gate | Observed result | Evidence under `build/reports/` |
|---|---|---|
| Full/Simple Kotlin compilation with Compose reports | Passed | `styles-final-repeat/compile-and-unit/` |
| Ordinary unit tests | Full 2,773 and Simple 2,956; zero failures, errors or skips | `styles-final-repeat/compile-and-unit/result.json` and fresh XML |
| Owning Full screenshot verification | 404 unchanged captures; zero added, recorded or changed | `styles-final-repeat/screenshots-full/` |
| Owning Simple screenshot verification | 384 unchanged captures; zero added, recorded or changed | `styles-final-repeat/screenshots-simple/` |
| Expected-fixture coverage | Exact union of all 420 fixtures; no missing or extra fixture | `styles-final-repeat/capture-catalog-coverage.json` |
| Full `staticAnalysis` | Passed in 206 seconds with the project 6-GiB Gradle heap and one worker | `styles-final-static/{command,result}.json` and `gradle.log` |
| Current-source Full/Simple app and test APKs | Not accepted; resource failures below | `styles-final-repeat/analysis-and-apks/` and `styles-final-resume/analysis-and-apks-full/` |
| Current-source device repeat | Not run; requires the accepted APK set | Earlier device results remain earlier-source evidence |
| Three-state, two-series performance comparison | Not run; no accepted original profileable APK | `performance.md` |

The screenshot tasks also execute ordinary tests. Their fresh XML contains 3,071 Full tests and 3,266 Simple tests, with zero failures or errors. The one Full and 29 Simple assumption skips match the earlier catalog exactly; no new skip was introduced. Full and Simple capture counts overlap and must not be added to the 420 unique fixture count. All expected-file hashes remained unchanged during each final phase. Only the four explicitly approved images changed from the original branch baseline; the other 416 and the one-percent threshold remain unchanged.

The preceding combined analysis/APK attempt stopped at the monitored disk cutoff after 279 seconds, with exit 130. The minimum sampled free space was 1,806,983,168 bytes. Sampling does not identify which host allocation caused the drop. Its APK and analysis results are not accepted. Five closed native packaging directories were then removed after path, ignored/tracked-file and open-file checks. All protected native inputs, APKs, reports and fixture hashes were preserved.

A sequential analysis/Full-APK retry then failed after 143 seconds because its forced 2-GiB Gradle heap caused GC thrashing. It produced no accepted APK set. The independent static-only repeat used the project's declared 6-GiB heap, one worker and no build cache. It passed all 759 scheduled tasks (23 executed), with no disk cutoff, unchanged source and unchanged fixtures. The earlier failures remain recorded and are not relabelled as passes.

The user-authorized cleanup removed only verified closed generated outputs, reproducible downloaded artifacts and eligible idle emulator-pool data through the standard locking tool. Shared caches were checked for active JVMs, open files and current working directories before removal. Installed SDK/NDK/toolchains, source, reports, native build inputs, active devices and unrelated work were preserved. Exact inventories and hash checks are in `styles-disk-cleanup/`; the benchmark-owned cleanup report is in that worktree's `build/research/styles-performance-orchestration/authorized-cache-cleanup-1792277399146346000/`. After the final static gate, observed free space was about 3.5 GiB. This is below the reviewed 8-GiB APK and 12-GiB benchmark admission floors. No further shared-cache cleanup is planned.

The local Simple asset is an exact copy of the repository CI fixture, SHA-256 `cadaff4fd5daa0cfeec5182e428a3207d926dd7ea62b917edadc21876a92194c`. The normal bundle verifier and Simple tests accept it. It enables local build verification; it does not provide production relay credentials or establish live connectivity.

Independent source reviews found no remaining actionable issue. The shared-surface/screen step is complete; combined device and performance acceptance remains open. No main integration, push, remote CI, archive or publication was performed.

## Initial dependency gate

- Baseline source: `829f5a90b04aabcd4d8a4d0820190c9f48f3de98`.
- The published beta BOM resolves Foundation, UI, and Runtime 1.13.0-beta01 and Material3 1.5.0-beta01.
- Android CLI installed `platforms/android-37.1` revision 1.
- The new CI minor-platform regression failed before the change and passed after it. The native dependency graph and tool pinning suites passed all 36 tests.
- Build convention sources compiled during the first Gradle gate. That run failed because the new worktree had no ignored libXray artifact directory. It does not establish app compatibility.
- The standard verifier accepted the existing libXray artifact set from the main checkout: SHA-256 `c5865cc277651bc7964500333566f9c540fe5c4053881aa109785c8d5b24ab35`. The next gate selects it with the supported `ripdpi.prebuiltXrayAarDir` property.
- Dependency gate reports are under `build/reports/styles-dependency-gate*.log`.
- The final dependency gate passed: `:app:checkGithubFullDebugAarMetadata`, `:app:compileGithubFullDebugKotlin`, `:app:compileGithubSimpleDebugKotlin`, and `:build-logic:convention:test`. It ran with Compose reports enabled and completed in 4 minutes 37 seconds after the host build slot became available. A diagnostic confirmed compile SDK 37.1 for app, service, and the benchmark module. The published libXray artifacts were verified through the normal build task. No native build was skipped.
- The beta dependencies add deprecation warnings for existing BasicTextField/VisualTransformation and sheet APIs. They do not block compilation. Changing editing-state or modal behavior is outside this migration.
- Component, screenshot, device, and performance validation remain pending. The successful dependency gate does not cover provisional source candidates in the other worktrees.

## Styles foundation test cycle

- The first test-only run failed in `:app:compileGithubFullDebugUnitTestKotlin` because the RDS style properties, resolver helpers, and Button `visualStyle` parameter did not exist. The log is `build/reports/styles-red.log` (2 minutes 44 seconds after admission). This is the expected RED result, not a dependency failure.
- Core properties, resolver modifiers, cached recipes, the theme catalog getter, and Button/IconButton source are now integrated in the job worktree. The same ten-test class is running through the build gate. No component test, screenshot, or device result is claimed yet.

- The first implementation run failed during Full source compilation: exposing an optional experimental `CommonStyle` parameter also required opt-in at existing calls that did not pass it. The failure is in `build/reports/styles-wave1-tests.log` (23 seconds). Small non-restartable overloads now keep the old signatures and delegate to the styled implementations. This keeps opt-in local to explicit style usage. The next run includes the theme and shared-control test families.

- Compatibility overloads passed Full main and all unit-test source compilation. The selected run executed 77 theme/shared-control tests: 75 passed, two failed after a static composition-local update (generic color and cached Button dark/contrast). The log is `build/reports/styles-wave1-compatibility.log` (1 minute 52 seconds). The resolver still used the initial values. Dependent component integration waits for the adapter invalidation fix and a repeat of the same tests.

## Local storage recovery

- The original profileable ARM64 source build reached native compilation with the required two-job budget, then failed with `No space left on device`. This run does not establish a benchmark APK or performance result.
- The user authorized removal of recoverable caches. Cleanup removed 16 unused Homebrew and downloaded JDK archives, totalling 976,570,357 bytes. Open files were excluded. The installed SDK, device images, worktrees, source, reports, active `uv` cache, and shared native caches were preserved.
- Exact deleted paths and before/after filesystem readings are in `build/reports/styles-disk-cleanup/cleanup.json`. Free space after cleanup was about 15 GiB. The resumed benchmark command checks free space again inside the build slot before it starts.

- Other host builds consumed space while our commands waited. A second cleanup removed 34 further complete, closed Homebrew downloads (528,928,065 bytes); no Homebrew process was running. The two archive cleanups removed 1,505,498,422 bytes in total. See `build/reports/styles-disk-cleanup/cleanup-second.json`. Free space at this second checkpoint was about 7.8 GiB; benchmark admission still requires 14 GiB.

- A further bounded scan covered 191 registered worktrees. The inactive `RIPDPI-ui-action-clarity` engine intermediates directory was ignored by Git, had no tracked or open files, and was removed (2.323 GiB logical size). App intermediates that contain Roborazzi PNG results were preserved. The removal report is `build/reports/styles-disk-cleanup/cleanup-intermediates.json`.
- The Docker builder endpoint was verified as the local `desktop-linux` Unix socket. No cache records older than 24 hours were eligible. Standard unused-build-cache pruning then reported 2.779 GB removed. Images, containers, and volumes were not pruned. Logs are `build/reports/styles-disk-cleanup/docker-builder-prune*.log`. Host free space returned to about 14 GiB; the first two resume attempts kept the 14 GiB admission check.
- The composition-time context signal still failed the same two theme tests (75/77 passed, 57 seconds; `build/reports/styles-wave1-local-context.log`). Published Runtime/UI sources show snapshot observer notification can run before the new node composition-local map is installed. The next implementation writes the context signal from `SideEffect`, after UI changes are applied, and retains the resolver and its animations. Runtime validation remains pending in `build/reports/styles-wave1-after-apply.log`.

- The post-apply signal passed all 77 selected theme/shared-control tests: zero failures, errors, or skipped tests (`build/reports/styles-wave1-after-apply.log`, 36 seconds). Both previous stale-local regressions now pass. Full production and unit-test sources compiled. This completes the adapter/Button/IconButton unit gate; input, surface, screenshot, device, and performance acceptance remain pending.

- The second benchmark preflight found only 12.47 GiB and exited before Gradle. A fresh output inventory then estimated 4–8 GiB of remaining peak growth for ARM64-only: common Rust dependencies and two transports are cached; JNI link, helpers, cloudflared, and Android packaging remain. The next controlled attempt requires 12 GiB at admission and monitors disk space every 1–2 seconds. It cancels only its owned build if free space reaches 3 GiB. This adjusts a storage estimate after the ABI reduction and cache warm-up; build, source-artifact, test, and performance gates stay the same. Completion remains unverified.

## Input and motion test cycle

- The first input gate compiled Full production and all unit-test sources, then ran 87 selected theme/shared-control tests. Five failed: one real Chip hit-target regression, one incorrect disabled-field focus assertion, and three motion checks whose snapshot notification had not been drained before manual clock advancement. See `styles-wave2-characterization.log`.
- After explicit UI synchronization, the unchanged animated-recipe positive control passed. Two recipe-replacement tests still failed with the final Blue color at the midpoint. This confirms that replacing the resolver closed active animation entries. See `styles-wave2-synchronized.log` (85 passed, two failed).
- The resolver now observes current default/caller recipes and the composition-local context through one post-apply input state. It retains animation entries across recipe replacement. Chip scales its painting without changing hit-test coordinates.
- The repeat passed all 87 tests, with zero failures, errors, or skipped tests. This includes actual touch near the Chip edge, disabled/read-only input, selection semantics, caller style precedence, static theme updates, and three strict animation midpoint checks. Evidence: `styles-wave2-stable-recipes.log` and `styles-wave2-test-results/` (27 seconds). Device IME and dropdown acceptance remain pending.
- Five further inactive Android compiled-output directories were verified as ignored, untracked, and closed before removal. They contained 537,776,128 allocated bytes. Reports and images were excluded. Exact paths and free-space readings are in `styles-disk-cleanup/cleanup-compiled.json`.

## Shared surfaces and decoration

- The shared-surface and feedback sources passed Full production and all unit-test source compilation. The selected gate passed all 99 theme, shared-control, and design-source-rule tests, with zero failures, errors, or skipped tests (`styles-wave3-components.log`, 53 seconds; XML in `styles-wave3-test-results/`).
- The four added surface tests cover actual disabled Card touch, SettingsRow toggle semantics and required height, Preset selection/disabled behavior, and the RouteOpportunity trailing action callback. Independent source review found no actionable issue in the 18-file slice and confirmed all 21 original public signatures/defaults.
- Screenshot and device evidence are still required. Screen recipe candidates have not yet entered the combined source tree.

- The exact Full/Simple APK gate failed at `:app:verifyEmbeddedRelayBundle` because `app/src/simple/assets/embedded-relay-bundle.json` is absent in both the main and job checkouts. This is the existing required local Simple input. No substitute bundle or bypass was added. Full and AndroidTest packaging are retried separately; the Simple APK remains blocked until the real bundle is available. Evidence: `styles-wave3-apks.log` (1 minute 11 seconds).
- The combined source tree passed the file LoC gate: 4,204 files, zero new violations, baseline exemptions, or growth violations. Architecture health also remains at its 21 existing indicators, with zero new or worsened entries.

## Combined screen and device gate

- Full and Simple Kotlin compilation passed with Compose reports enabled. Full production and all unit-test/instrumentation sources compiled. The selected 99 tests passed with zero failures, errors, or skipped tests. Full Debug and AndroidTest APK assembly passed. The gate completed in 1 minute 14 seconds (`styles-wave4-final-components-apks.log`; XML in `styles-wave4-test-results/`; compiler output in `styles-final-compiler/`).
- Independent review found no actionable issue in the 34-file screen slice and new instrumented test. All 57 helper call sites replace existing paint; layout, inset, and feature geometry contracts remain in place.
- Device correctness passed all three exact tests in 5.443 seconds, with no failures, errors, or skipped tests. Device: `RIPDPI_Pool_1`, `emulator-5554`, API 37, ARM64 Google APIs. Actual soft IME visibility and Done handling, dropdown selection/disabled behavior, and two-action disconnect confirmation passed. The pool lease was released normally; secure IME setting was restored from 1 to its original 0; crash log was empty.
- Installed ARM64 app SHA-256: `677092717613995bb9d824ebf736734790cf04c9472460fb77b446aac47087f5`; AndroidTest SHA-256: `9ef7839294c9e5641d4086d999a14129e132ce836168607774bfca49ac3b0f5f`. Report, original metadata, source dirty status, stdout, and JUnit XML are preserved in `build/reports/styles-device/accepted-20261017T130031496Z/`. The two earlier attempts failed in the local runner/setup before tests; no downgrade, uninstall, or app-data reset was used.
- All requested broad unit, Full/Simple screenshot, and static-analysis gates are queued separately. Device correctness does not establish screenshot parity, network/service acceptance, or performance.

## Broad gate findings

- The complete Full unit suite passed all 2,764 tests with zero failures, errors, or skipped tests. The requested combined Full/Simple unit command still failed because Simple test packaging requires the absent local relay bundle. No Simple test ran. Evidence: `styles-final-unit-tests.log` and preserved `styles-final-unit-results/Full/`.
- Full screenshot verification ran 298 screenshot tests: six image mismatches, zero errors, and one existing assumption skip (`RtlVariantGalleryTest.rtlHandshakeTimeline`). All 2,764 non-screenshot unit tests also passed in that command. Simple remained blocked by the required bundle. The six mismatches are preserved in `styles-final-screenshot-results/images/`; no golden was changed. Dependency-only comparison is pending, so these are not yet classified as dependency changes or Styles regressions.
- Static analysis completed Android lint and other module checks, then failed on two app detekt issues introduced by the migration: styled SettingsRow reached the parameter limit, and TextField reached the method-length limit. The fixes group styled-only presentation flags in `SettingsRowDecoration` while preserving the legacy API, and extract private field-state preparation. No suppression or baseline is added. Repeat validation is pending.
- Serialized cleanup removed the job's own Rust Cargo target local state (4.77 GiB allocated). Gradle declares it `@LocalState`; generated JNI/helper outputs, debug symbols, APKs, and reports were preserved. Exact size, hashes, and free-space readings are in `styles-disk-cleanup/cleanup-own-cargo-local-state.json`.

## Local Simple bundle

- The user asked to prepare the missing bundle locally. The existing CI action materializes `scripts/fixtures/embedded-relay-bundle/ci-fixture.json` for UI builds without release secrets. The job now uses the same committed fixture, copied byte for byte into the ignored Simple asset. SHA-256: `cadaff4fd5daa0cfeec5182e428a3207d926dd7ea62b917edadc21876a92194c`. Provenance is in `build/reports/styles-simple-bundle-provenance.json`.
- This input supports local packaging, parser/seeder contract tests, and UI validation. Its documentation addresses do not establish working relay connectivity or release configuration. No build gate or seeder requirement was changed. The Simple gates will be repeated with this input.

## Onboarding device inspection

- The accepted ARM64 app was installed and its SHA-256 was checked on a leased API 37 device. English and Arabic were selected through Settings and the real language picker. Both font-scale 2.0 flows passed page 0 to page 1 using actual CTA taps. English CTA bounds were 960 by 169 pixels; Arabic bounds were 960 by 203 pixels on a 1,080-pixel root. Labels and chevrons were visible in all four inspected PNG files. Arabic activity configuration contained `ldrtl`, with the arrow pointing left.
- Device font scale and app locales were restored to `1.0` and `[]`; the crash log was empty and the lease exited normally. Evidence is in `build/reports/styles-onboarding-device/large-font-20261017T133224817825Z/`. The earlier font-scale 1.0 inspection also showed a full-width CTA and a successful page transition. These runtime results do not replace screenshot classification or performance measurements.

## Quality-limit fixes

- The repeated combined gate passed `staticAnalysis`, Full/Simple Kotlin compilation, Full Debug assembly, and Full AndroidTest assembly in 2 minutes 31 seconds. All 99 selected theme, shared-control, and design-source-rule tests passed, with zero failures, errors, or skipped tests. Evidence: `build/reports/styles-lint-fixes-components-apks.log` and preserved XML in `styles-lint-fixes-test-results/`.
- Independent review confirmed the legacy SettingsRow signature and defaults, the styled decoration call site, and the unchanged four TextField state flags. No quality suppression or baseline was added. The five Styles-specific onboarding screenshot regressions still require a fix; the Logs maximum-font actual is pixel-identical to the dependency-only build.

## Materialized Simple input and complete local suites

- The normal bundle verifier and both `CiRelayBundleFixtureTest` parser/seeder tests passed with the committed CI fixture. Full and Simple Kotlin compilation passed with Compose reports enabled. Both Debug APK assembly tasks passed. The local native inputs are the eight hash-verified, previously built ARM64 outputs selected through the supported prebuilt properties; this is UI validation, not a new multi-ABI native or performance result.
- The complete Full unit suite passed 2,764 tests; Simple passed 2,947 tests. Both suites had zero failures, errors, or skips. The combined gate completed in 4 minutes 8 seconds. Evidence: `build/reports/styles-simple-final/{gradle.log,result.json,xml/}`. Source was clean at `af60448259dd0641f8db6052d519d1512d59b88b` before and after the command. APK paths and hashes are in the result manifest.
- The test filter uses `CiRelay*FixtureTest`. The earlier literal class name contains `Bundle`, which the existing native task-name heuristic treats as a release-like request. That unintended source build was cancelled before tests; its own closed Cargo local state was removed. No heuristic or native verification gate was changed.
- A repeat of the three exact device tests passed on leased `emulator-5556`, API 37 ARM64, in 5.503 seconds, with no failures, errors, skips, or crash output. App hash was `2ee0bc115475cad63cbb414dbbc476bdffc88476b81404c808cf94d1c805e1ac`; test hash was `7f9e4b2ed4297f1c159867da67018ea0e46ffc765854c6615b79cba13fea4240`. Font scale, app locales, and IME setting were preserved. Evidence: `build/reports/styles-device/quality-repeat-20261017T142838223531Z/`. The later APK manifest from the prebuilt-input gate identifies different binaries; those binaries are not the ones used by this device repeat.

## Screenshot synchronization and dependency classification

- The five onboarding mismatches measured the CTA at the correct 380 by 52 pixels, including the drawing scope. Changing graphics-layer implementations, removing placement or border paint, draining layout only before the first draw, and using an attached View capture did not fix the mismatch. A second draw alone also failed. Processing pending Compose layout after a draw passed. These controlled variants were restored and are not production fixes.
- The shared screenshot helper now uses the public `androidx.compose.ui.test.v2.runEmptyComposeUiTest` environment for its custom Activity and Intent. It waits for Compose idle after content and viewport setup, then captures one bitmap. It retains the existing size, font scale, inspection mode, static motion, paths, and comparison threshold. It closes the ActivityScenario even after a mismatch. No warm-up bitmap, sleep, private scheduler loop, or golden update is used.
- The focused control compiled and ran six tests: all five previous onboarding failures passed, including large-font, Arabic, and Persian cases. Only `logsStreamCopyActionsMaximumFont` still failed. Evidence: `build/reports/styles-layer-diagnosis/public-test-environment/` and the preceding isolation summaries. Full and Simple screenshot suites must still be repeated with the final helper.
- On the original source and dependencies, Logs maximum-font passed the existing one-percent threshold, with about 0.43 percent changed pixels; it was not pixel-identical to its golden. Updating dependencies and SDK without any UI source change raised the difference to about 2.01 percent. The Styles build's earlier actual was pixel-identical to that dependency-only actual. The dark counterpart and final synchronized capture still need review before any fixture update can be proposed.
- Independent source review also identified a spring difference in the beta API: it carries normalized progress velocity across a changed property delta. Typed value-space spring parity tests and a small RDS bridge are being prepared. No runtime result or completed motion acceptance is claimed yet.

## Typed spring parity

- The four new tests failed before the bridge. Button and IconButton already differed on the nontrivial forward leg at 64 ms: reference scale was `0.9967755`, versus about `0.9941764` in Styles. Chip's painted edge differed. Disabling motion left an active Button at `0.991623` instead of the required `0.98` target. This reproduces trajectory and motion-off differences; it does not separately isolate the source-proven reverse-velocity defect.
- Button, IconButton, and Chip now retain the original typed `animateFloatAsState` and `animateDpAsState` springs and their default visibility thresholds. The remembered default style reads their State values during attached resolution at layer zero. The caller's layer-one override still wins. Color tweens remain in CommonStyle. Disabling animations removes active spring animators, including when the target does not change. Public component APIs and input geometry are unchanged.
- The same four tests then passed, together with all 99 existing theme, control, and design-source-rule tests: 103 tests, zero failures, errors, or skips. Comparisons cover the forward leg, eight frames after release, settling, painted radius/scale, motion-off, and caller precedence. The tests require visible motion before comparing trajectories. No test tolerance was widened after RED.
- Evidence: `build/reports/styles-layer-diagnosis/spring-{red,green}/` and `spring-root-decision.json`. GREEN completed in 48 seconds; expected screenshot hashes were unchanged. Independent source review found no important issue in the typed bridge, state-read phase, layer precedence, cancellation, or component integration. Broad combined gates still require a repeat after this production change.

## Combined typed-spring source gate

- Clean source `e2097511ef1fad5c8a8fe72b411e8ea842d8d7f5` passed Full/Simple Kotlin compilation with Compose reports and all 2,768 Full and 2,951 Simple unit tests. Both suites had zero failures, errors, or skips. Evidence: `build/reports/styles-combined-final/compile-and-unit/`.
- Owning Full screenshot verification produced 403 captures: 400 unchanged, three mismatches. Simple produced 383 captures: 381 unchanged, two mismatches. Shared Full/Simple mismatch actuals were pixel-identical. Existing assumption skips remain reported in the XML. All 420 expected fixtures remained unchanged.
- The final analysis/APK phase was cancelled after repeated dependency downloads caused by omission of the shared read-only cache. It establishes no new APK or static-analysis result. The next combined gate uses that cache.

## Remaining screenshot classification

- Original Logs captures passed in both themes at 0.4296 percent changed pixels. The dependency/SDK-only source failed at 2.0134 percent light and 1.9697 percent dark. Both Styles actuals were pixel-identical to their dependency-only counterpart. This isolates the dependency/SDK set, not Foundation alone.
- With dependency-only source and the old capture helper, both Diagnostics cases passed: zero changed pixels for the navigation shell and 0.9401 percent for the catalog. Changing only to the public synchronized helper produced 1.1395 and 1.5747 percent respectively. Both actuals and comparison images were pixel-identical to the final Styles source.
- These controlled runs used owning verify tasks and fresh XML/Roborazzi JSON. No expected image was updated. Only these four exact fixtures have been proposed for explicit approval. Reports are preserved under `build/reports/styles-counterfactuals/` and the visual-baseline worktree's `build/research/visual-*` directories.

## Owned navigation styles

- The coverage review found owned paint in BottomNavBar and RipDpiNavRail. Both now use cached CommonStyle recipes, including focus and selected paint, with the existing interaction source. Their original typed tween State values are read during attached style resolution. Caller styles affect the outer shell; adaptive geometry, hit targets, semantics, RTL and layout remain component-owned.
- Independent review found no production issue. Five added tests cover actual focus pixels, press/cancel/callback and top-edge touch behavior, outer caller precedence, rail selection, and a cached recipe after theme-provider changes.
- The exact candidate passed 142 selected unit tests with zero failures, errors, or skips. Architecture health had zero new or worsened indicators; the file-size gate had zero violations. Evidence: `build/reports/styles-navigation/checkpoint/`, `architecture-health.log`, and `file-loc.log`.
- An initial direct test-task invocation omitted Roborazzi verify mode. Its eleven rendered methods are explicitly rejected as visual evidence in `checkpoint/screenshots-navigation/verification-mode-audit.json`. The owning-task repeat ran eleven tests: nine passed and the two known Diagnostics cases failed. Fresh actuals of both failures were pixel-identical to source e209. The candidate and 420 expected fixture hashes were unchanged. Evidence: `build/reports/styles-navigation/verify-checkpoint/`. Broad checks, final APK device checks and performance remain pending.

## Approved fixture refresh scope

- After review of the controlled comparisons, the user explicitly approved only Logs maximum-font light/dark, Diagnostics maximum-font navigation shell, and Diagnostics maximum-font catalog. The isolated golden specialist owns those four PNG files. Other fixtures and the one-percent comparison threshold remain unchanged. The record/verify outcome is recorded separately when observed.
- The diagnostic worktree's closed task-output cache was removed under the build gate after ignored/tracked/open-file checks. Actual free space increased by 978,415,616 bytes; all 1,033 protected report, native-input, and expected-fixture hashes remained unchanged. Counterfactual evidence was copied and rehashed into `build/reports/styles-counterfactuals/manifest.json`.

## Observed approved fixture refresh

- The isolated golden specialist recorded exactly the three owning methods that produce the four approved images. Record passed three tests and four captures in 120 seconds. The following owning verify passed the same three tests and four unchanged captures in 24 seconds, with zero failures, errors, or skips. Each expected RGBA hash equals its reviewed counterfactual actual. All other 416 fixtures and the one-percent threshold were unchanged.
- The four-file commit is `4eab6fbeac5e70a1789de03d8d87153686054403` in the isolated worktree and `1fb903842` after local integration. Fresh XML/JSON, commands, old/new comparison images, four hashes, and the result are preserved in `build/reports/styles-approved-goldens/`. No preview PNG was copied into the fixture directory. Hooks passed after initializing the pinned Rust-skills submodule through the standard command.
- An earlier isolated record attempt stopped at the three-GiB disk cutoff before any test or fixture write. It is not a test result. Closed native packaging intermediates in the primary job were removed after ignored, tracked-file, open-file and canonical-path checks; all 440 protected native inputs, expected images and existing APK hashes were unchanged. Actual recovered space was 2,448,388,096 bytes. The diagnostic worktree's obsolete PNG/APK-free transforms and Kotlin DSL cache then recovered 2,386,628,608 bytes, with 9,352 protected file hashes unchanged. Exact evidence is in `styles-disk-cleanup/cleanup-pre-golden.json` and `styles-counterfactuals/cleanup-visual-transforms.json`.
- The next combined gate binds the refreshed fixtures and final navigation sources to one clean commit. Earlier APKs remain earlier evidence.
