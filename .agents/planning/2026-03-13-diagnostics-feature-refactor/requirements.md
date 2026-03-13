# Diagnostics Feature Refactor Requirements

## Problem Statement

The diagnostics feature currently depends on two oversized, tightly related files:

- `core/diagnostics/src/main/java/com/poyka/ripdpi/diagnostics/DiagnosticsManager.kt`
- `app/src/main/java/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScreen.kt`

Both files concentrate multiple responsibilities in one place, which makes behavior-preserving maintenance risky. The goal of this work is to produce a safe, specification-first, test-first refactor plan that decomposes these files by cohesive responsibility while preserving current externally observable behavior.

## In-Scope Files

### Primary Refactor Targets

- `core/diagnostics/src/main/java/com/poyka/ripdpi/diagnostics/DiagnosticsManager.kt`
- `app/src/main/java/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScreen.kt`

### Required Context For Safe Planning

These files are not primary refactor targets, but they define the current feature boundary and must be respected by the plan:

- `app/src/main/java/com/poyka/ripdpi/activities/DiagnosticsViewModel.kt`
- `core/diagnostics/src/test/java/com/poyka/ripdpi/diagnostics/DiagnosticsManagerTest.kt`
- `app/src/test/java/com/poyka/ripdpi/activities/DiagnosticsViewModelTest.kt`
- `app/src/test/java/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScreenTest.kt`
- `app/src/test/java/com/poyka/ripdpi/ui/screenshot/*`

## Feature-Level Behavior That Must Remain Stable

### Diagnostics Manager Behavior

- `initialize()` must keep importing bundled profiles, cleaning archive cache, and subscribing to handover-triggered automatic probing.
- `startScan()` must keep shaping scan requests according to profile kind, path mode, DNS preferences, and service/runtime constraints.
- active scan progress, cancellation behavior, and failure handling must remain stable for user-visible flows.
- automatic handover probes must keep respecting cooldowns, hidden execution rules, and profile selection safety.
- session, context, snapshot, telemetry, event, and approach data exposed to the app layer must remain semantically equivalent.
- resolver recommendation behavior must preserve ranking, temporary application, persistence, and per-network preference updates.
- share summary and archive outputs must preserve content semantics, redaction rules, included files, and fallback session selection behavior.
- approach statistics and detail loading must preserve the current aggregation semantics.

### Diagnostics Screen Behavior

- route-level effect handling must keep dispatching share/save/archive actions to the host.
- pager synchronization and selected-section behavior must remain stable.
- all existing diagnostics sections must remain available with the same user-facing content and interaction affordances.
- current visibility rules for scan progress, resolver recommendation actions, strategy-probe reports, bottom sheets, empty states, and share action states must remain stable.
- local UI behavior that should remain local, such as event-list auto-scroll state, must continue to behave the same after extraction.
- the screen must continue to consume immutable UI state and event lambdas rather than deep owner objects.

### Feature Boundary Stability

- the refactor must preserve the current behavior of the diagnostics feature across the manager, viewmodel, and screen boundary before any contract redesign is considered.
- the public `DiagnosticsManager` interface and the current `DiagnosticsUiState` shape should remain stable during the early refactor slices unless a change is required to fix a clear defect.

## Non-Goals

- redesigning the diagnostics UX, IA, copy, or navigation model
- changing the diagnostics database schema or archive format
- replacing the existing feature contract with a new architecture up front
- fully refactoring `DiagnosticsViewModel.kt` as part of this scope
- extracting speculative interfaces or abstractions that do not create a real test or ownership seam
- changing behavior for aesthetic or stylistic reasons alone

## Constraints

- work spec-first and test-first
- treat this as a safe refactor, not a redesign
- preserve externally observable behavior unless a clear defect is discovered and intentionally fixed
- prefer characterization coverage before structural change
- refactor incrementally with rollback-safe checkpoints
- decompose by cohesive responsibility, not arbitrary line count
- call out coupling that should intentionally remain due to lifecycle or performance reasons
- derive responsibilities from repository code, not screenshots or assumptions
- do not overwrite or interfere with other active planning/refactor loops in `.agents/planning`

## Acceptance Criteria

- a later implementation can refactor `DiagnosticsManager` into a narrow coordinator plus cohesive collaborators without changing feature behavior
- a later implementation can refactor `DiagnosticsScreen` into route/container, content, section, modal, and helper files without changing feature behavior
- the refactor order is explicitly test-first and rollback-safe
- characterization, focused unit, UI, and integration/contract tests are clearly separated in the plan
- the plan identifies the real interaction boundary between manager outputs, viewmodel shaping, and screen rendering
- the plan avoids pushing new responsibilities into `DiagnosticsViewModel` as a side effect of manager/screen decomposition
- the plan is implementation-ready for a later Ralph run and does not include implementation itself

## Risk Constraints

- no big-bang rewrite of manager and screen in the same commit
- no simultaneous manager API redesign and screen decomposition before characterization tests are green
- no movement of business logic into Compose files or UI-only logic into core modules
- no movement of screen structure concerns into `DiagnosticsViewModel` just to reduce line count
- every extraction must compile, run, and be revertable independently
