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
- The Docker builder endpoint was verified as the local `desktop-linux` Unix socket. No cache records older than 24 hours were eligible. Standard unused-build-cache pruning then reported 2.779 GB removed. Images, containers, and volumes were not pruned. Logs are `build/reports/styles-disk-cleanup/docker-builder-prune*.log`. Host free space returned to about 14 GiB; the benchmark keeps its 14 GiB admission check.
- The composition-time context signal still failed the same two theme tests (75/77 passed, 57 seconds; `build/reports/styles-wave1-local-context.log`). Published Runtime/UI sources show snapshot observer notification can run before the new node composition-local map is installed. The next implementation writes the context signal from `SideEffect`, after UI changes are applied, and retains the resolver and its animations. Runtime validation remains pending in `build/reports/styles-wave1-after-apply.log`.

- The post-apply signal passed all 77 selected theme/shared-control tests: zero failures, errors, or skipped tests (`build/reports/styles-wave1-after-apply.log`, 36 seconds). Both previous stale-local regressions now pass. Full production and unit-test sources compiled. This completes the adapter/Button/IconButton unit gate; input, surface, screenshot, device, and performance acceptance remain pending.
