# DiagnosticsViewModel Refactor Plan

Target file: `app/src/main/java/com/poyka/ripdpi/activities/DiagnosticsViewModel.kt`

Goal:
Safely reduce this oversized ViewModel to a focused screen-state and intent-handling component.

Planning constraints:
- Inspect the current file and verify which responsibilities it owns today.
- Produce a test-first plan with characterization coverage before structural change.
- Preserve emitted UI state, user flows, filtering behavior, formatting, export/share behavior, and side effects.

Target architecture:
- ViewModel owns screen state production and user intent handling only.
- Formatting, filtering, redaction, mapping, archive/share workflows, and domain orchestration move into dedicated collaborators.
- Dependencies and boundaries are explicit for each extracted responsibility.
- Prefer small use cases, mappers, formatters, coordinators, or reducers over one large state holder.

Testing strategy to plan:
- Characterization tests for existing public ViewModel behavior.
- Unit tests for extracted reducers, mappers, filters, redactors, and share/archive collaborators.
- Coroutine and Flow tests for state emission and event handling.
- Integration-style tests only where public behavior spans multiple collaborators.

Isolation note:
This planning workspace is intentionally separate from `.agents/planning/2026-03-13-advanced-settings-screen-refactor` so it does not interfere with the other running refactor loop.
