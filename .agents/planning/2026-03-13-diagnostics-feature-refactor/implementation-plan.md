# Diagnostics Feature Refactor Implementation Plan

## Checklist

- [ ] Step 1: Add missing characterization coverage for current diagnostics feature behavior
- [ ] Step 2: Extract pure screen helpers and chart utilities without changing screen behavior
- [ ] Step 3: Extract screen sections and bottom-sheet content into focused files
- [ ] Step 4: Extract resolver recommendation and connectivity DNS planning from the manager
- [ ] Step 5: Extract share-summary and archive-building collaborators from the manager
- [ ] Step 6: Extract manager query and persistence collaborators
- [ ] Step 7: Extract scan workflow and automatic probe coordination behind the manager
- [ ] Step 8: Run full diagnostics feature validation, final cleanup, and handoff preparation

## Implementation Guidance

Convert the design into a series of implementation steps that will build each component in a test-driven manner following agile best practices. Each step must result in a working, demoable increment of functionality. Prioritize best practices, incremental progress, and early testing, ensuring no big jumps in complexity at any stage. Make sure that each step builds on the previous steps, and ends with wiring things together. There should be no hanging or orphaned code that isn't integrated into a previous step.

## Step 1: Freeze Current Feature Behavior With Characterization Tests

Objective:
Create the missing safety net before any production extraction.

Implementation guidance:

- extend `DiagnosticsManagerTest` for current public behavior that is not yet pinned well enough:
  - `loadSessionDetail`
  - `loadApproachDetail`
  - `buildShareSummary`
  - `keepResolverRecommendationForSession`
- extend `DiagnosticsScreenTest` to cover section switching, empty states, busy share actions, and bottom-sheet visibility rules that matter during file moves
- add diagnostics-specific screenshot tests only where the current visual structure is part of the contract and easy to keep stable
- avoid changing production code in this step except for test tags or preview helpers if a test truly needs them

Test requirements:

- new characterization tests must describe current behavior, not desired redesign behavior
- screenshot tests, if added, must capture existing current layouts rather than a cleaned-up future version
- keep `DiagnosticsViewModelTest` green because it is the feature-contract layer between manager and screen

Integration with previous work:

- this is the baseline; no structural extraction starts before this step is green

Validation gate:

- `./gradlew :core:diagnostics:testDebugUnitTest --tests com.poyka.ripdpi.diagnostics.DiagnosticsManagerTest`
- `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.activities.DiagnosticsViewModelTest --tests com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsScreenTest`
- if screenshot tests are added: `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.ui.screenshot.*`

Suggested commit boundary:

- `test(diagnostics): add characterization coverage for manager, screen, and feature contract`

Demo:

- no user-visible behavior changes
- the diagnostics feature now has a stronger regression harness that documents current behavior

## Step 2: Extract Pure Screen Helpers And Chart Utilities

Objective:
Reduce risk inside `DiagnosticsScreen.kt` by moving pure and near-pure helpers first.

Implementation guidance:

- extract chart interpolation helpers, tone/palette helpers, and diagnostics-specific visual helpers into focused files such as:
  - `DiagnosticsCharts.kt`
  - `DiagnosticsTonePalette.kt`
- keep `DiagnosticsScreen` and `DiagnosticsRoute` public signatures unchanged
- keep all moved functions package-private/internal to avoid widening the API surface
- do not move owner state upward; keep local UI behavior local

Test requirements:

- add focused unit tests for extracted chart interpolation helpers before moving them
- keep existing screen tests and any new screenshot tests green after the move

Integration with previous work:

- builds directly on Step 1 characterization coverage
- prepares the screen for section extraction without changing section ownership yet

Validation gate:

- `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsScreenTest`
- run the new helper unit tests

Suggested commit boundary:

- `refactor(diagnostics-ui): extract chart and palette helpers from diagnostics screen`

Demo:

- diagnostics screen renders exactly as before
- chart and helper logic now live in focused files with direct tests

## Step 3: Extract Screen Sections And Bottom-Sheet Content

Objective:
Turn `DiagnosticsScreen.kt` into a route/container plus focused section and modal files.

Implementation guidance:

- extract sections by cohesive responsibility, not arbitrary size:
  - overview
  - scan
  - live
  - sessions
  - events
  - share
- extract modal hosts/content into `DiagnosticsBottomSheets.kt`
- keep `DiagnosticsScreen` responsible only for scaffold shell, section host, and modal host wiring
- pass only the required `DiagnosticsUiState` slices and callbacks to each section
- keep local UI state such as event-list scroll behavior in the section that owns it
- keep reusable diagnostics-specific rows/cards in shared UI files within the same package

Test requirements:

- before extraction, add any missing screen tests for the sections being moved
- after extraction, re-run all screen tests and screenshot tests
- do not add new behavior in the same slice as the file move

Integration with previous work:

- uses the helper files from Step 2
- preserves the current screen contract for `DiagnosticsRoute`

Validation gate:

- `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsScreenTest`
- `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.activities.DiagnosticsViewModelTest`

Suggested commit boundary:

- `refactor(diagnostics-ui): split diagnostics screen into sections and bottom sheets`

Demo:

- diagnostics route still works the same
- screen code is now organized around sections and modal hosts instead of one giant file

## Step 4: Extract Resolver Recommendation And Connectivity DNS Planning

Objective:
Remove pure recommendation and DNS-planning logic from the manager first, before touching async workflow.

Implementation guidance:

- add or extend focused tests for:
  - recommendation ranking edge cases
  - preferred/current path tie-breaking
  - endpoint parsing
  - connectivity DNS target expansion
- extract collaborators such as:
  - `ResolverRecommendationEngine`
  - `ConnectivityDnsTargetPlanner`
- keep `DefaultDiagnosticsManager` delegating to these collaborators without changing its public API
- leave recommendation application and settings persistence in the manager or a follow-on collaborator only if that split is clearly beneficial

Test requirements:

- new collaborator tests must be focused and deterministic
- existing `DiagnosticsManagerTest` must remain the final contract check

Integration with previous work:

- manager behavior remains protected by the baseline tests
- no screen or viewmodel contract changes are required here

Validation gate:

- `./gradlew :core:diagnostics:testDebugUnitTest --tests com.poyka.ripdpi.diagnostics.DiagnosticsManagerTest`
- run the new recommendation/planner unit tests

Suggested commit boundary:

- `refactor(diagnostics-core): extract resolver recommendation and dns target planning`

Demo:

- diagnostics scans still produce the same resolver recommendations and request shaping
- recommendation logic is now isolated and directly testable

## Step 5: Extract Share Summary And Archive Builders

Objective:
Separate export/share artifact creation from manager orchestration while preserving golden contracts.

Implementation guidance:

- keep current archive file contents, redaction rules, and fallback behavior exactly stable
- extract collaborators such as:
  - `DiagnosticsShareSummaryBuilder`
  - `DiagnosticsArchiveBuilder`
  - optional small internal helper for archive cache pruning or zip writing if needed
- keep manager responsible only for calling the builder and recording export metadata
- preserve current archive payload types unless a private internal seam is enough

Test requirements:

- extend characterization coverage for share summary if still needed
- keep archive golden tests and archive failure/fallback tests green
- add focused builder tests only for logic not already well-covered by manager contract tests

Integration with previous work:

- reuses the stable recommendation and request behavior from Step 4
- keeps screen and viewmodel behavior unchanged

Validation gate:

- `./gradlew :core:diagnostics:testDebugUnitTest --tests com.poyka.ripdpi.diagnostics.DiagnosticsManagerTest`

Suggested commit boundary:

- `refactor(diagnostics-core): extract share summary and archive builders`

Demo:

- share summary and archive generation behave exactly as before
- archive shaping is no longer mixed with scan lifecycle code

## Step 6: Extract Manager Query And Persistence Collaborators

Objective:
Remove history-query and report-persistence responsibilities from the manager.

Implementation guidance:

- add missing characterization tests for:
  - session detail selection rules
  - approach detail aggregation/filtering
  - report persistence and event bridging if needed beyond current tests
- extract collaborators such as:
  - `DiagnosticsSessionQueries`
  - `DiagnosticsReportPersister`
- reuse existing `ApproachAnalytics.kt` rather than duplicating aggregation logic
- keep collaborator boundaries aligned with repository ownership:
  - query/read concerns together
  - write/persist concerns together

Test requirements:

- collaborator unit tests should cover isolated logic only when the logic is truly pure enough to justify them
- manager contract tests remain required after the extraction

Integration with previous work:

- manager now delegates pure builders plus query/persistence concerns
- screen behavior remains protected indirectly through viewmodel tests

Validation gate:

- `./gradlew :core:diagnostics:testDebugUnitTest --tests com.poyka.ripdpi.diagnostics.DiagnosticsManagerTest`
- `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.activities.DiagnosticsViewModelTest`

Suggested commit boundary:

- `refactor(diagnostics-core): extract diagnostics query and persistence services`

Demo:

- session detail, approach detail, and persisted scan results behave exactly as before
- manager now reads as a coordinator over focused core services

## Step 7: Extract Scan Workflow And Automatic Probe Coordination

Objective:
Move the highest-risk asynchronous logic behind explicit workflow collaborators once the pure logic has already been split.

Implementation guidance:

- extract scan lifecycle behavior into `DiagnosticsScanWorkflow`
- extract handover-triggered hidden-probe behavior into `AutomaticProbeCoordinator` if the remaining coordinator is still too broad
- keep active scan ownership explicit and narrow
- do not add new concurrency semantics; preserve current lifecycle behavior first
- keep rollback easy by maintaining the old manager entry points as thin delegates

Test requirements:

- rely on existing manager tests for:
  - raw-path and in-path scan flows
  - automatic handover probes and cooldown rules
  - failure behavior
  - active progress clearing
- add focused workflow tests only if the extraction creates a clean deterministic seam

Integration with previous work:

- depends on prior extraction of pure builders and persistence/query collaborators
- should leave `DefaultDiagnosticsManager` close to its intended final coordinator role

Validation gate:

- `./gradlew :core:diagnostics:testDebugUnitTest --tests com.poyka.ripdpi.diagnostics.DiagnosticsManagerTest`
- `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.activities.DiagnosticsViewModelTest`

Suggested commit boundary:

- `refactor(diagnostics-core): extract scan workflow and automatic probe coordination`

Demo:

- diagnostics scans, hidden automatic probes, and cancellation still behave the same
- manager implementation is now mostly delegation and explicit coordination

## Step 8: Final Feature Validation And Handoff Prep

Objective:
Finish the refactor with full feature validation, minimal cleanup, and a stable handoff point for future implementation work.

Implementation guidance:

- remove only dead code made obsolete by the extractions
- keep any coupling that remains for lifecycle or performance reasons, and document why it remains
- if manager-to-screen pressure still exists, only introduce app-layer mapping helpers that reduce feature coupling without redesigning the public contract
- avoid opportunistic rewrites outside the planned diagnostics feature boundary

Test requirements:

- run the full unit-test gate
- run screenshot tests if they were added
- run static analysis before final handoff

Integration with previous work:

- this closes the incremental refactor sequence and confirms the feature is still stable end-to-end

Validation gate:

- `./gradlew testDebugUnitTest`
- `./gradlew staticAnalysis`

Suggested commit boundary:

- `refactor(diagnostics): finalize manager and screen decomposition`

Demo:

- diagnostics feature works as before with a thinner manager, decomposed screen files, and green regression coverage

## How To Keep The Feature Working While Both Files Are Being Decomposed

- keep `DiagnosticsManager` public methods and flows stable until the majority of the manager extractions are complete
- keep `DiagnosticsRoute` and `DiagnosticsScreen` public entry points stable during UI extraction
- move code into new files first, then simplify call sites in a follow-up within the same slice
- never mix contract changes with large file moves in one step
- keep `DiagnosticsViewModel` behavior stable and resist using it as a temporary dumping ground
- treat `DiagnosticsViewModelTest` as a contract suite even though the file itself is not being refactored here

## Definition Of Done

- `DiagnosticsManager` is reduced to a narrow, explicit coordinator over cohesive collaborators
- `DiagnosticsScreen` is reduced to route/container composition with focused section, sheet, and helper files
- current feature behavior is protected by characterization, focused unit, UI, and contract tests
- archive/share/recommendation/scan/session flows remain behaviorally stable
- all targeted gates and final gates are green
- the refactor remains incremental, revertable, and implementation-ready for a later Ralph run
