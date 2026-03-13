# AdvancedSettingsScreen Refactor Requirements

## Goal

Create a spec-first, test-first refactor plan for `app/src/main/java/com/poyka/ripdpi/ui/screens/settings/AdvancedSettingsScreen.kt` that safely decomposes the file without changing user-visible behavior.

## Scope

In scope:
- Split route/container concerns from pure UI rendering.
- Extract feature-section composables into dedicated files.
- Move non-UI transformation, mapping, formatting, and section-building logic out of the screen file.
- Preserve current navigation, state transitions, feature flags, conditional visibility, and side effects.
- Add characterization coverage before any structural refactor.

Out of scope:
- Product changes, copy changes, layout changes, or design polish.
- Changing `SettingsUiState` semantics or `SettingsViewModel` behavior.
- Altering nav graph behavior, host-pack semantics, strategy-chain semantics, or persistence rules.
- Reworking unrelated settings screens.

## Current-State Inventory

The inspected file is `6155` lines and currently mixes container logic, screen composition, section-specific UI, local editor state, preview fixtures, and pure-ish helper logic.

### Route and container responsibilities

`AdvancedSettingsRoute(...)` currently:
- Collects `uiState` and `hostPackCatalog` from `SettingsViewModel`.
- Collects `SettingsEffect.Notice` inside `LaunchedEffect(viewModel)` and maps it to an inline `AdvancedNotice`.
- Translates UI events into `viewModel.updateSetting(...)` calls.
- Owns normalization and side-effect orchestration for:
  - `22` toggle settings
  - `31` text-confirm settings
  - `12` option settings
- Calls direct view-model actions for host-pack apply/refresh, forget learned hosts, clear remembered networks, and reset actions.

### Root screen responsibilities

`AdvancedSettingsScreen(...)` currently:
- Computes screen-wide derived flags such as `visualEditorEnabled`, `showFakeTlsSection`, `showQuicFakeSection`, `showActivationWindowProfile`, and host-pack apply enablement.
- Builds dropdown option lists from resources and state.
- Owns root-local ephemeral UI state for:
  - pending host-pack dialog selection
  - selected host-pack target mode
  - selected host-pack apply mode
- Renders all top-level lazy-list items for:
  - diagnostics history
  - command-line overrides
  - proxy
  - desync and fake-packet controls
  - activation window
  - protocol toggles
  - host autolearn
  - network strategy memory
  - HTTP parser evasions
  - HTTPS/TLS prelude
  - UDP hint
  - QUIC fake profile
  - hosts / host-pack catalog

### Existing composition boundaries already present in the file

The screen already has viable extraction seams, but they are all private and co-located:
- Shared controls:
  - `SettingsSection`
  - `AdvancedDropdownSetting`
  - `AdvancedTextSetting`
  - `ActivationRangeEditorCard`
  - `ActivationBoundaryField`
  - `ProfileSummaryLine`
  - `SummaryCapsuleFlow`
- Feature/profile cards:
  - activation window
  - adaptive split
  - adaptive fake TTL
  - fake TLS
  - fake payload library
  - host fake
  - fake approximation
  - HTTP parser
  - TLS prelude
  - QUIC fake profile
  - host-pack catalog and dialog
  - host autolearn

### Local ephemeral UI state that must remain local

The refactor must preserve local, non-business state in UI layers:
- `notice` in the route as transient banner state sourced from effects.
- Host-pack dialog state in the screen/hosts section.
- Draft text state in `AdvancedTextSetting`.
- Draft range-input state in `ActivationRangeEditorCard`.

### Non-UI logic currently embedded in the file

The file also owns logic that should move out of the screen file over time:
- Formatting:
  - host-pack source summary
  - host-pack timestamps
  - fake-profile labels
  - host-autolearn last-update summary
- Strategy and normalization helpers:
  - TLS prelude editor step building
  - TLS prelude chain rewriting
  - primary split-marker updating
  - activation-range normalization helpers
- Option/section model building:
  - adaptive split preset options
  - adaptive fake TTL mode options
  - many `remember*Status(...)` functions that derive labels, bodies, and tones from `SettingsUiState`

## Behavioral Invariants

The refactor must preserve all of the following:

### Navigation and container behavior

- `AdvancedSettingsRoute` remains the screen entry point used by the nav host.
- Back navigation still calls the existing `onBack`.
- View-model sharing through the settings graph remains unchanged.

### State ownership

- `SettingsUiState` and `HostPackCatalogUiState` remain the source of truth.
- Route-level side effects stay outside pure content sections.
- Local draft/edit state stays local unless business logic requires promotion.

### Conditional visibility and feature flags

- Command-line mode continues to disable visual editors and show the restricted banner.
- Section visibility continues to follow existing `SettingsUiState` derived properties.
- Dynamic editors continue to appear/disappear exactly as they do now, including:
  - adaptive split manual vs preset controls
  - adaptive fake TTL fixed vs adaptive/custom fields
  - TLS prelude mode-specific fields
  - QUIC host override visibility
  - hosts blacklist vs whitelist editor

### Input and save semantics

- Text fields keep current dirty-state, validation, helper-text, and save-button behavior.
- Range editors keep current validity rules and save enablement.
- Invalid input remains unsaved and must not fire view-model updates.
- Normalization behavior for markers, ranges, TTL values, and QUIC host strings remains unchanged.

### Side effects and mutations

- All existing `viewModel.updateSetting(...)` keys and value semantics remain intact.
- Existing reset actions, host-pack apply behavior, refresh behavior, and catalog status behavior remain intact.
- Notice-effect mapping remains intact.

### Preview and screenshot stability

- Existing previews continue to compile.
- If a screenshot baseline is introduced for the advanced screen, it becomes the visual rollback signal for future refactor slices.

## Required Test Coverage Before and During Refactor

### Characterization coverage first

Add baseline coverage for current behavior before moving sections or logic:
- Compose UI tests covering representative render states and key interactions.
- Characterization tests for pure helper behavior already exposed from the file.
- Route/binder tests for event-to-view-model mapping once an extraction seam exists.

### Compose UI coverage

At minimum, cover:
- command-line override banner and disabled visual controls
- host-pack apply vs refresh enablement
- dynamic section visibility across fake-TLS / adaptive-TTL / TLS-prelude / QUIC / hosts modes
- save-button enablement for text editors and range editors
- callback firing for representative toggles, dropdowns, and save actions

### Screenshot / golden coverage

Roborazzi already exists in the repo via `captureRipDpiScreenshot(...)`, `recordScreenshots`, and `verifyScreenshots`.

The plan should add at least one advanced-screen screenshot scene for a stable representative state if the baseline cost is acceptable.

### Unit-test coverage for extracted logic

Every extracted mapper/formatter/section-model builder must gain direct unit coverage, including:
- host-pack formatting helpers
- TLS prelude editor logic
- activation-range normalization helpers
- any newly introduced status-model or section-model factories

## Refactor Success Criteria

The refactor is successful when:
- The oversized file is decomposed into route, shared controls, section files, and pure logic/model files.
- All current user-visible behavior is preserved.
- Each extraction step is protected by tests added before or alongside the slice.
- The final pure content sections are stateless or near-stateless, driven by immutable inputs and event lambdas.
- The route remains the only layer coupled to `SettingsViewModel`, flows, and side effects.
