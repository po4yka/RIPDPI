# Diagnostics Feature Refactor

Create a PDD/TDD refactor plan for this combined diagnostics feature scope:

- `core/diagnostics/src/main/java/com/poyka/ripdpi/diagnostics/DiagnosticsManager.kt`
- `app/src/main/java/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScreen.kt`

Goal:
Safely refactor these two tightly related oversized files together as one diagnostics feature refactor, while preserving behavior.

Primary constraints:
- Work spec-first and test-first.
- This is a safe refactor, not a redesign.
- Preserve externally observable behavior unless a change is required to fix a clear defect discovered during the work.
- Refactor incrementally with rollback-safe checkpoints.
- Prefer characterization coverage before structural change.
- Do not assume architecture from screenshots alone; inspect the real code and derive responsibilities from the repository.

Combined refactor intent:

1. `DiagnosticsManager.kt`
- Reduce it from a multi-responsibility God Object into a smaller coordination layer plus cohesive collaborators.
- Identify and separate concerns such as:
  - diagnostics orchestration
  - recommendation logic
  - session / probing workflow handling
  - persistence / history bridging
  - archive / export building
  - any JSON/result shaping or other mixed concerns present in the actual code
- Keep the remaining manager/coordinator narrow and explicit.
- Avoid speculative abstraction and avoid extracting interfaces unless they create a real seam for testing or ownership.

2. `DiagnosticsScreen.kt`
- Reduce it from a giant screen file into a clear Compose screen structure.
- Separate:
  - Route/container concerns
  - pure content rendering
  - section composables
  - chart/palette/interpolation/helper logic
  - share/session/live/event blocks
  - any local UI-state logic that should remain local
- Follow Compose best practices:
  - unidirectional data flow
  - immutable UI state passed downward
  - event lambdas passed upward
  - avoid passing heavyweight owners deep through the tree
  - hoist state only to the lowest appropriate owner
  - keep extracted content previewable where practical

Important combined-architecture requirement:
- Treat these two files as one feature boundary.
- Plan how the `DiagnosticsManager` decomposition and `DiagnosticsScreen` decomposition interact.
- Identify whether the screen currently depends too directly on mixed manager outputs, and define safer boundaries such as UI models, mappers, or screen-focused collaborators if needed.
- Freeze behavior at the feature level before restructuring internals.

Testing strategy requirements:
The plan must explicitly follow TDD/PDD and safe-refactor discipline.

Before any significant extraction, define and add characterization coverage for current behavior, including as appropriate:
- unit tests for `DiagnosticsManager` public behavior
- regression tests for export/archive/recommendation/session flows
- Compose UI tests for `DiagnosticsScreen` key states, visibility rules, and interactions
- screenshot/golden tests for diagnostics screen sections if screenshot tooling already exists in the repo
- unit tests for extracted chart helpers, UI-model mappers, recommendation logic, and builders
- integration-style tests only where they meaningfully protect cross-component behavior

The plan must distinguish:
- characterization tests that freeze current behavior
- new focused unit tests for extracted collaborators
- UI tests for screen behavior
- integration/contract tests for manager-to-screen or manager-to-feature outputs when needed

Required deliverables:
Produce:
1. `requirements.md`
2. `design.md`
3. `implementation-plan.md`

`requirements.md` must include:
- problem statement
- in-scope files
- feature-level behavior that must remain stable
- non-goals
- constraints
- acceptance criteria
- risk constraints

`design.md` must include:
- current architecture problems in both files
- dependency/coupling analysis between manager and screen
- target architecture for the diagnostics feature
- proposed file/module breakdown
- ownership boundaries
- test strategy
- migration strategy
- risk analysis and rollback strategy

`implementation-plan.md` must include:
- explicit test-first milestones
- exact order of safe extractions
- which tests are added before each extraction
- validation gates after each slice
- suggested commit boundaries
- how to keep the feature working while both files are being decomposed
- definition of done

Planning rules:
- Prefer decomposition by cohesive responsibility, not arbitrary line count.
- Preserve behavior over elegance.
- Call out any coupling that should remain for lifecycle/performance reasons.
- Keep the plan implementation-ready for a later Ralph run.
- Do not implement yet.

Be careful: there is another loop already running with refactoring of another component in the application. Do not override its documentation and do not affect it.
