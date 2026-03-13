# AdvancedSettingsScreen Implementation Plan

## Checklist

- [ ] Step 1: Add characterization harness and baseline coverage for current screen behavior
- [ ] Step 2: Extract route bindings and notice mapping out of the screen file
- [ ] Step 3: Extract shared logic/helpers into pure files with direct unit coverage
- [ ] Step 4: Extract shared UI building blocks and preserve editor-state behavior
- [ ] Step 5: Extract low-risk top-level sections into dedicated files
- [ ] Step 6: Extract complex desync, HTTP, HTTPS, and activation-window sections with section models
- [ ] Step 7: Extract QUIC, hosts/host-pack, and host-autolearn sections, then stabilize screenshots and previews

## Step 1: Add characterization harness and baseline coverage for current screen behavior

Objective:
Create a stable test seam for the current pure content and lock down representative behavior before any real decomposition begins.

Tests added first:
- Add `AdvancedSettingsScreenCharacterizationTest` for representative states and key interactions.
- Cover command-line override banner and disabled visual controls.
- Cover dynamic visibility for at least:
  - adaptive fake TTL fields
  - TLS prelude random-record fields
  - QUIC host override
  - blacklist vs whitelist host editors
- Cover representative callbacks:
  - a toggle action
  - a dropdown selection
  - a text-setting save action
- If the current private content function blocks testing, add the smallest possible non-functional access seam and immediately freeze it with the tests above.
- Add a first advanced-screen Roborazzi scene only if a stable representative preview can be rendered without brittle scrolling assumptions.

Code extraction/refactor:
- No structural extraction yet.
- Only permit zero-behavior changes required to expose the content for testing and/or screenshot capture.

Validation gates:
- `./gradlew :app:testDebugUnitTest`
- If screenshot coverage is added: `./gradlew verifyScreenshots`
- Spot-check that existing previews still compile.

Rollback-safe checkpoint:
- The production screen remains in one file.
- The only change is added test coverage plus a minimal test seam.
- If later steps fail, this commit still improves safety with no behavioral risk.

Demo:
- A failing test would catch visible regressions in current advanced-screen behavior before any refactor lands.

## Step 2: Extract route bindings and notice mapping out of the screen file

Objective:
Remove container-side event translation and effect mapping from the oversized screen file while keeping the route API and behavior unchanged.

Tests added first:
- Add direct unit tests for extracted route-binding helpers.
- Cover representative mappings for:
  - toggle updates
  - text normalization and update keys
  - option selection branches
  - TLS prelude update orchestration
  - activation-range save/update behavior
  - notice-effect to banner-model mapping

Code extraction/refactor:
- Move `AdvancedSettingsRoute(...)` into its own file if it is still co-located with content.
- Extract route-side `when` logic into binder/helper functions or a small route adapter object.
- Keep the callback contract into the pure screen unchanged.

Validation gates:
- Baseline characterization tests from Step 1 remain green.
- New binder tests pass.
- `./gradlew :app:testDebugUnitTest`

Rollback-safe checkpoint:
- Navigation and `SettingsViewModel` wiring are unchanged.
- The pure content file still renders the same UI with the same inputs.
- If reverted, only route organization is lost; behavior remains intact.

Demo:
- The route file becomes short and readable, and route semantics are enforced by unit tests instead of buried inline `when` blocks.

## Step 3: Extract shared logic/helpers into pure files with direct unit coverage

Objective:
Move non-UI transformation and formatting logic out of the screen file before section extraction starts.

Tests added first:
- Expand or add unit tests for:
  - host-pack formatting helpers
  - TLS prelude editor-step and chain-rewrite helpers
  - split-marker normalization/update helpers
  - activation-range normalization/update helpers
  - profile-label formatters
- Add tests for any newly introduced pure status/section-model builders.

Code extraction/refactor:
- Move pure helpers into dedicated files grouped by concern.
- Prefer pure functions over composable `remember...` helpers when the logic does not require composition.
- Where strings are currently computed inside composables, introduce small immutable model builders that can be tested without Compose.

Validation gates:
- Existing helper tests remain green.
- Characterization tests remain green.
- `./gradlew :app:testDebugUnitTest`

Rollback-safe checkpoint:
- UI structure is still mostly unchanged.
- Logic has only been moved, not rewritten.
- Existing callers still pass through the same values and state objects.

Demo:
- The main screen file loses formatting and normalization noise, and extracted logic is independently testable.

## Step 4: Extract shared UI building blocks and preserve editor-state behavior

Objective:
Move reusable controls out of the screen file so later section extraction does not duplicate editor behavior.

Tests added first:
- Add direct Compose tests for extracted shared controls:
  - `AdvancedTextSetting` dirty-state, invalid-state, helper-text, and save enablement
  - `ActivationRangeEditorCard` save enablement and validation
  - dropdown callback forwarding
- Keep at least one full-screen characterization test covering these controls in context.

Code extraction/refactor:
- Move shared controls into a dedicated file:
  - `SettingsSection`
  - `AdvancedDropdownSetting`
  - `AdvancedTextSetting`
  - `ActivationRangeEditorCard`
  - `ActivationBoundaryField`
  - `ProfileSummaryLine`
  - `SummaryCapsuleFlow`
- Preserve local `rememberSaveable` behavior exactly.

Validation gates:
- Shared-control Compose tests pass.
- Full-screen characterization tests remain green.
- `./gradlew :app:testDebugUnitTest`

Rollback-safe checkpoint:
- Feature sections still live in the main screen file.
- The extracted controls are generic and behavior-locked by tests.

Demo:
- Shared controls now live in one place and can be reused by extracted sections without changing save/validation behavior.

## Step 5: Extract low-risk top-level sections into dedicated files

Objective:
Start shrinking the main screen by moving the simplest top-level sections first.

Tests added first:
- Add or extend Compose UI tests that assert section presence and representative interactions for:
  - diagnostics history
  - command-line overrides
  - proxy
  - protocols
  - network strategy memory
- Assert that enable/disable behavior still follows command-line mode.

Code extraction/refactor:
- Move these sections into dedicated files with explicit immutable inputs and event lambdas.
- Keep section ordering in the root screen unchanged.
- Keep the root screen responsible only for assembling sections and screen-local state.

Validation gates:
- Baseline characterization tests remain green.
- Section-specific UI tests pass.
- `./gradlew :app:testDebugUnitTest`
- `./gradlew staticAnalysis`

Rollback-safe checkpoint:
- Only low-risk sections have moved.
- The root screen still owns the same state and passes through the same callbacks.

Demo:
- The main screen file becomes materially shorter, with low-risk sections rendered from dedicated files and no behavior drift.

## Step 6: Extract complex desync, HTTP, HTTPS, and activation-window sections with section models

Objective:
Tackle the heaviest logic cluster in small internal slices while using model builders to keep behavior readable and testable.

Tests added first:
- Add unit tests for extracted section-model builders/status builders covering:
  - adaptive split status/options
  - adaptive fake TTL status/options
  - fake payload library status and badge derivation
  - fake TLS status and summaries
  - HTTP parser group summaries/status
  - TLS prelude status and summaries
  - activation-window summaries/status
- Add Compose tests for representative visibility and interactions in each section.

Code extraction/refactor:
- Extract the desync cluster into dedicated files, but do not rewrite event contracts.
- Replace composable-only status derivation with pure builders where possible.
- Keep section-local editor state local.
- Preserve the exact order of controls and dividers inside each section.

Validation gates:
- New unit tests for section models pass.
- Step 1 characterization tests remain green.
- `./gradlew :app:testDebugUnitTest`
- If an advanced-screen screenshot exists: `./gradlew verifyScreenshots`

Rollback-safe checkpoint:
- The route and root screen contract are still unchanged.
- Each complex section can be reverted independently because its extraction is isolated by tests.

Demo:
- The largest behavior-heavy chunk of the screen is decomposed into section files backed by unit-tested models instead of inline `when` forests.

## Step 7: Extract QUIC, hosts/host-pack, and host-autolearn sections, then stabilize screenshots and previews

Objective:
Finish the decomposition by moving the remaining stateful sections and locking the final structure with screenshot/previews.

Tests added first:
- Add Compose tests for:
  - host-pack dialog open/apply/dismiss behavior
  - host-pack apply vs refresh enablement
  - QUIC fake host override visibility and save flow
  - host-autolearn action enablement and summary visibility
- Add unit tests for any extracted host-pack or host-autolearn model builders.
- Add or finalize a Roborazzi advanced-screen scene if it was deferred earlier.

Code extraction/refactor:
- Move QUIC, hosts/host-pack, and host-autolearn sections into dedicated files.
- Keep host-pack dialog state local to the hosts section unless the root shell truly needs it.
- Move preview fixture builders or stable preview scenes into a dedicated preview file if that reduces noise in the screen file.
- Remove dead code and unused private helpers from the original file.

Validation gates:
- All Compose and unit tests pass.
- `./gradlew :app:testDebugUnitTest`
- `./gradlew verifyScreenshots`
- `./gradlew staticAnalysis`

Rollback-safe checkpoint:
- By this point, each feature area lives in its own file with behavior locked by tests.
- If one extracted section regresses, it can be reverted independently without undoing the whole refactor.

Demo:
- `AdvancedSettingsScreen.kt` is reduced to a route shell plus screen assembly, with dedicated feature files, pure helper/model files, and regression coverage protecting the current UX.
