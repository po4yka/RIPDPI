# Implementation Plan

## Checklist

- [ ] Step 1: Expand characterization coverage around the public ViewModel API
- [ ] Step 2: Split the screen contract and replace positional `combine` casting with typed snapshot inputs
- [ ] Step 3: Extract common formatting, tone, and filtering helpers
- [ ] Step 4: Extract redaction plus snapshot and context mapping
- [ ] Step 5: Extract overview, live, sessions, events, and share section reducers
- [ ] Step 6: Extract approach and strategy-probe presentation mapping
- [ ] Step 7: Extract session-detail loading and audit auto-open workflow
- [ ] Step 8: Extract share/archive and resolver-action coordinators
- [ ] Step 9: Slim the ViewModel to wiring, local state, and intent handling only
- [ ] Step 10: Optional follow-up reuse and cleanup once diagnostics behavior is stable

## Plan

1. Step 1: Expand characterization coverage around the public ViewModel API
   Objective: Turn the current `DiagnosticsViewModelTest` suite into a complete safety net for every public behavior that will be moved later.
   Guidance: Add missing tests for dismiss methods, event and probe selection, sensitive-detail toggling, session and event search/filter permutations, `setEventAutoScroll`, `startInPathScan`, `cancelScan`, share/archive fallback session selection, busy-state transitions, and pending audit cancellation. Reuse the existing fake manager and fixture builders rather than introducing new test infrastructure.
   Test requirements: All new tests assert only public `uiState`, `effects`, and manager delegation; do not reach into private helpers.
   Integration: This step changes tests first and should require little or no production code change beyond exposing already observable behavior clearly.
   Demo: The diagnostics screen behavior is unchanged, but the test suite now fails on any regression in public state, effect emission, or workflow transitions.

2. Step 2: Split the screen contract and replace positional `combine` casting with typed snapshot inputs
   Objective: Remove the most brittle structural problem without altering behavior.
   Guidance: Move screen-facing enums, UI models, and effects into `DiagnosticsContract.kt` or equivalent. Introduce a typed `DiagnosticsSourceSnapshot` plus a helper that builds it from the upstream flows instead of indexing into `values[0]`, `values[1]`, and so on.
   Test requirements: Run the full characterization suite unchanged. Add a narrow test only if the new snapshot helper needs direct coverage.
   Integration: The ViewModel still builds the same `DiagnosticsUiState`, but now receives explicit typed input, which makes reducer extraction safe.
   Demo: The app still behaves the same, while the core state-wiring logic is reviewable and no longer depends on positional casts.

3. Step 3: Extract common formatting, tone, and filtering helpers
   Objective: Remove pure utility logic from the ViewModel first.
   Guidance: Extract byte, duration, timestamp, pluralization, and outcome-tone logic into formatter classes or small objects. Extract session and event filter matching into dedicated helpers. Keep APIs small and pure.
   Test requirements: Before extraction, ensure characterization covers the visible formatting and filtering outcomes that matter. After extraction, add focused unit tests for formatters and filters covering current edge cases and label expectations.
   Integration: The ViewModel delegates to pure collaborators while still owning the same filter state and intent methods.
   Demo: Search, filter chips, and displayed value formatting remain unchanged, and the new helper tests provide faster feedback than the full ViewModel suite.

4. Step 4: Extract redaction plus snapshot and context mapping
   Objective: Isolate the most sensitive mapping logic behind dedicated collaborators.
   Guidance: Introduce a `DiagnosticsRedactor`, `DiagnosticsSnapshotMapper`, and `DiagnosticsContextMapper`. Move visible-versus-redacted field building, transport-specific snapshot fields, context grouping, and context warnings into these collaborators.
   Test requirements: Add characterization first for hidden versus visible sensitive fields and for context warning behavior. Add unit tests for mapper and redactor combinations after extraction.
   Integration: Session detail and overview/live reducers consume mapped snapshot and context models rather than rebuilding them inline.
   Demo: Toggling sensitive details continues to show the same fields, redaction rules remain identical, and the ViewModel file shrinks without changing UI output.

5. Step 5: Extract overview, live, sessions, events, and share section reducers
   Objective: Break `buildUiState` into section-sized presentation builders.
   Guidance: Introduce a small `DiagnosticsUiStateReducer` that delegates to section reducers for overview, live, sessions, events, and share. Pass in typed snapshot data, selected ids, filter state, and already-mapped rows or helpers. Keep reducers pure.
   Test requirements: Existing characterization continues to assert section output. Add direct reducer tests for important branch logic such as health derivation, section forcing during active scan, warning selection, and share preview composition.
   Integration: The ViewModel still exposes one `uiState`, but now assembles it by delegating to section reducers instead of one monolithic method.
   Demo: Overview cards, live metrics, sessions list, events feed, and share preview remain stable while reducer tests make future edits local and faster.

6. Step 6: Extract approach and strategy-probe presentation mapping
   Objective: Move the most verbose humanization logic into dedicated presentation collaborators.
   Guidance: Extract approach row/detail mapping, strategy-probe report mapping, candidate sorting, strategy-signature field formatting, and fake-profile/autolearn label humanization. Keep these collaborators independent from the ViewModel and `viewModelScope`.
   Test requirements: Characterize the current approach and strategy-probe behavior before moving anything else. Preserve and extend direct assertions for candidate ordering, suite labels, signature fields, aggressive parser text, fake payload labels, and adaptive TTL output. Add mapper-specific unit tests after extraction.
   Integration: The approaches reducer and session-detail mapper consume the extracted strategy-probe mappers and formatters.
   Demo: The approaches section remains visually identical, but the logic behind its labels and rankings is now isolated, reusable, and unit-testable.

7. Step 7: Extract session-detail loading and audit auto-open workflow
   Objective: Separate detail-loading orchestration from state building.
   Guidance: Introduce `LoadDiagnosticsSessionDetail` and `LoadDiagnosticsApproachDetail` use cases plus a `DiagnosticsAuditAutoOpenCoordinator`. The coordinator watches pending audit session id, sessions, and active progress to decide when to load finished detail. Keep selection and dismissal state in the ViewModel.
   Test requirements: Add or tighten characterization around `selectSession`, `toggleSensitiveSessionDetails`, `dismissSessionDetail`, strategy-probe candidate focus, auto-open after full audit completion, and pending auto-open cancellation. Add focused unit tests for the auto-open decision rules where practical.
   Integration: ViewModel intent methods become small wrappers that update local state and delegate loading logic to use cases and the coordinator.
   Demo: Selecting a session, toggling sensitive details, and finishing a full audit still open the same detail UI, but the ViewModel no longer owns the detail-loading workflow inline.

8. Step 8: Extract share/archive and resolver-action coordinators
   Objective: Remove the remaining effectful workflow logic from the ViewModel.
   Guidance: Introduce `DiagnosticsShareSummaryCoordinator`, `DiagnosticsArchiveCoordinator`, `KeepResolverRecommendation`, and `SaveResolverRecommendation`. The archive coordinator should own busy, success, and failure state transitions plus the effect payload for share versus save. Preserve current messages and target-session behavior.
   Test requirements: Add characterization for no-argument fallback behavior, busy-state transitions, success and failure end states, and exact emitted effects. Add unit tests for coordinator state transitions and target-session resolution.
   Integration: The ViewModel retains effect emission, but the workflow result comes from a coordinator instead of inline `runArchiveAction` logic.
   Demo: Share summary, share archive, save archive, and resolver actions behave exactly as before, while the workflow logic is independently testable.

9. Step 9: Slim the ViewModel to wiring, local state, and intent handling only
   Objective: Complete the architectural goal after all risky behavior has already been protected and extracted.
   Guidance: Delete obsolete private helpers from the ViewModel, keep only local selection/filter state, `uiState` composition wiring, public intent methods, and effect channel handling. Review constructor dependencies to ensure they reflect the extracted collaborators instead of one giant manager plus dozens of inline helpers.
   Test requirements: The full characterization suite and the new collaborator unit tests must all pass. Add a small smoke test if needed to confirm the ViewModel still publishes `uiState` and `effects` correctly with the new dependency graph.
   Integration: This is the final internal cleanup step; no UI screen changes should be necessary.
   Demo: `DiagnosticsViewModel.kt` becomes a focused screen component with preserved behavior and a much smaller review surface.

10. Step 10: Optional follow-up reuse and cleanup once diagnostics behavior is stable
   Objective: Harvest safe cleanup opportunities without expanding the refactor scope too early.
   Guidance: Evaluate whether extracted snapshot, context, or session-detail mappers can be shared with `HistoryViewModel`. Do this only after the diagnostics refactor is complete and fully green, and only if it does not interfere with unrelated work.
   Test requirements: Any reuse across screens must add or preserve coverage in both screen test suites.
   Integration: This is optional and should not block the primary refactor.
   Demo: Shared presentation logic reduces duplication without changing behavior in either screen.
