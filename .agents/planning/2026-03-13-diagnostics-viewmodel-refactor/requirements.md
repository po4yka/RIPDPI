# Requirements

## Scope

Create a planning-only refactor for `app/src/main/java/com/poyka/ripdpi/activities/DiagnosticsViewModel.kt`. No production refactor is implemented in this pass. The outcome is a safe, test-first sequence for shrinking the ViewModel to screen-state production and intent handling.

## Verified Current Responsibilities

Code inspection shows that `DiagnosticsViewModel.kt` currently owns all of the following:

1. Screen contract definitions in the same file.
   It declares section enums, tone and health enums, roughly two dozen UI model data classes, the top-level `DiagnosticsUiState`, `DiagnosticsEffect`, and the internal `ArchiveActionState`.

2. Local UI selection and filter state.
   The ViewModel owns `MutableStateFlow` values for section selection, profile selection, approach mode, selected session detail, selected probe, selected event, selected strategy-probe candidate, session filters, event filters, sensitive-detail visibility, pending audit auto-open, and archive workflow state.

3. Flow aggregation and typed state assembly.
   `uiState` combines `DiagnosticsManager` flows, settings, remembered-network data, active connection policy, and all local flows into a single `StateFlow<DiagnosticsUiState>`. The current implementation uses a wide `combine` with positional array casts.

4. Startup and screen-level side effects.
   The ViewModel calls `diagnosticsManager.initialize()` in `init` and also owns the audit auto-open behavior that waits for a finished session after a full raw-path audit.

5. Public intent handling.
   The file exposes section selection, profile selection, detail selection and dismissal, filter updates, scan start and cancel, resolver recommendation actions, summary sharing, archive sharing, and archive saving.

6. Section reducers and composite state building.
   `buildUiState` derives overview, scan, live, sessions, approaches, events, share state, and currently selected details in one method.

7. Domain-to-UI mapping.
   The file decodes JSON payloads, maps sessions, events, contexts, snapshots, remembered networks, resolver recommendations, strategy-probe reports, and approach/session detail models.

8. Filtering logic.
   Session and event filtering, filter toggling, and query matching are implemented in the ViewModel file.

9. Formatting and humanization.
   The file formats timestamps, byte counts, durations, live headlines and bodies, health labels, strategy signatures, autolearn text, fake payload labels, and outcome tones.

10. Redaction.
    Sensitive network and context fields are conditionally redacted when session detail visibility is off.

11. Share and archive workflow orchestration.
    The ViewModel requests share summaries, creates archives, emits share/save effects, and manages busy, success, and failure messages for archive actions.

12. A default in-file remembered-network store fallback.
    The file contains a non-persistent `defaultRememberedNetworkPolicyStore()` implementation used as a default constructor dependency.

## Current Safety Net

`app/src/test/java/com/poyka/ripdpi/activities/DiagnosticsViewModelTest.kt` already provides meaningful characterization coverage for:

1. Composite screen state across overview, live, sessions, and share.
2. Active-scan section forcing and profile selection.
3. Automatic probing and automatic audit behavior.
4. Resolver recommendation surfacing and delegation.
5. Strategy-probe ordering and detail mapping.
6. Session detail loading.
7. Approach detail loading and humanized signature rendering.
8. Snapshot detail transport fields.
9. Share-summary and archive effects.
10. Event source filtering.
11. Archive failure state.

## Characterization Gaps To Close Before Structural Change

The existing suite does not fully characterize several public behaviors yet. The refactor plan must add explicit tests for these before the related code moves:

1. `dismissSessionDetail`, `dismissApproachDetail`, `dismissEventDetail`, `dismissProbeDetail`, and the reset semantics that go with them.
2. `selectEvent`, `selectProbe`, and how focused state is reflected in `uiState`.
3. `toggleSensitiveSessionDetails`, including reloading detail and preserving redaction semantics.
4. Session path-mode filter, session status filter, and session text search.
5. Event severity filter, event search text, and `setEventAutoScroll`.
6. `startInPathScan` and `cancelScan`.
7. Share/archive fallback to the currently selected or latest session when no explicit session id is provided.
8. Archive busy-state transitions in addition to success and failure end states.
9. Manual section selection behavior when no scan is active.
10. Pending audit auto-open cancellation when a scan is cancelled.

## Required Outcome

The refactor is complete only when all of these remain true:

1. Public ViewModel behavior is preserved.
   The screen must emit the same user-visible `DiagnosticsUiState` values and `DiagnosticsEffect` events for the same inputs and user actions.

2. The ViewModel becomes a thin screen component.
   It may own intent entry points, local selection/filter state, `uiState` exposure, and effect emission, but not formatting-heavy mapping or domain workflow logic.

3. Responsibilities move to explicit collaborators.
   Formatting, filtering, redaction, mapping, share/archive workflow, and `DiagnosticsManager` orchestration must be handled outside the ViewModel.

4. Collaborators stay small and single-purpose.
   The design must avoid replacing one oversized ViewModel with one oversized state assembler.

5. Refactor slices stay behavior-safe.
   Each step must keep the application buildable and the diagnostics screen functional.

6. The plan remains local to the target area.
   It may note duplication with `HistoryViewModel`, but it must not require edits to unrelated planning documentation or depend on the other refactor loop.

## Boundary Requirements

The target design must enforce these boundaries:

1. ViewModel boundaries.
   Owns public intent methods, local screen state, state-flow wiring, and effect publishing only.

2. Reducer boundaries.
   Pure or near-pure reducers build section state from typed inputs and local selection/filter state. They do not call `DiagnosticsManager` directly.

3. Mapper boundaries.
   Mappers turn domain entities and decoded payloads into UI models. They can depend on formatters and redactors, but not on coroutine scope or Android lifecycle.

4. Formatter and redactor boundaries.
   Formatters and redactors operate on primitives and decoded domain models only. They do not know about flows, ViewModel lifecycle, or effect channels.

5. Use-case and coordinator boundaries.
   Use cases wrap `DiagnosticsManager` operations and related workflow logic. Archive/share coordinators may return state updates and effects, but do not build full-screen UI state.

6. Contract boundaries.
   The diagnostics screen contract may be split into dedicated files, but public screen-facing types and behavior must remain stable throughout the refactor.

## Testing Requirements

1. Characterization-first.
   Add or tighten public `DiagnosticsViewModel` characterization tests before extracting the responsibility they protect.

2. Unit tests for new collaborators.
   Every extracted filter, formatter, redactor, mapper, reducer, and share/archive coordinator gets direct unit coverage.

3. Coroutine and Flow coverage.
   State emission, effect emission, pending-audit auto-open, and archive workflow transitions must be verified with coroutine test utilities already used in this module.

4. Integration-style tests only where needed.
   Keep integration-style tests at the ViewModel level for flows that span multiple collaborators, such as share/archive workflows or audit auto-open completion.

5. No coverage regression.
   The existing `DiagnosticsViewModelTest` suite remains the top-level behavioral guardrail until the refactor is done.

## Non-Goals

1. No UI redesign.
   This refactor does not change diagnostics screen UX, copy, or information architecture.

2. No `DiagnosticsManager` redesign in this pass.
   Manager internals may be wrapped by use cases, but manager behavior is not the primary refactor target.

3. No broad cross-screen cleanup as a prerequisite.
   Reusing extracted mappers in `HistoryViewModel` is optional and should happen only after `DiagnosticsViewModel` behavior is locked down.
