---
task_id: UIX-1792228984398359
change: compose-common-styles
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: Publication is not authorized.
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RDS-STYLE-PARITY | UIX-1792229085066929 | Full/Simple unit tests and verifyScreenshots | Pending |
| REQ-RDS-STYLE-OVERRIDE | UIX-1792229082522189 | `styles-wave1-after-apply.log`: ten style tests plus existing theme/shared-control tests | Passed locally |
| REQ-RDS-STYLE-THEME | UIX-1792229083651465 | Theme, state, reduced-motion and control tests | Pending |
| REQ-RDS-STYLE-COMPATIBILITY | UIX-1792229081138948 | `styles-dependency-gate-final.log`: AAR metadata, Full/Simple compilation, build convention tests | Passed locally |
| REQ-RDS-STYLE-ACCEPTANCE | UIX-1792229086339829 | Local, screenshot, device and profileable evidence | Pending |

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
