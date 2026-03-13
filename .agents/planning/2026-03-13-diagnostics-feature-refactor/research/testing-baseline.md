# Diagnostics Testing Baseline

## Existing Coverage Found

### Manager Layer

`core/diagnostics/src/test/java/com/poyka/ripdpi/diagnostics/DiagnosticsManagerTest.kt` already covers:

- raw-path and in-path scan request shaping
- diversified DNS target expansion
- resolver recommendation ranking and persistence
- remembered per-network DNS-path behavior
- strategy-probe enrichment and remembered policy persistence
- automatic handover probing and cooldown behavior
- archive creation, redaction, fallbacks, and golden outputs
- approach summary aggregation
- scan failure paths and passive event failures

### ViewModel Contract Layer

`app/src/test/java/com/poyka/ripdpi/activities/DiagnosticsViewModelTest.kt` already covers:

- high-level `DiagnosticsUiState` composition
- active scan section switching
- strategy-probe and audit UI-model shaping
- resolver recommendation surfacing and manager delegation
- session detail loading
- approaches detail shaping
- share/archive effect behavior
- event filtering and archive failure state

### Screen Layer

`app/src/test/java/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScreenTest.kt` currently covers:

- resolver recommendation action rendering
- automatic audit matrix rendering
- automatic audit candidate bottom sheet content

### Screenshot / Golden Tooling

- Roborazzi is enabled in `app` via `ripdpi.android.roborazzi`.
- Screenshot support exists in `app/src/test/java/com/poyka/ripdpi/ui/screenshot/`.
- Golden-contract helpers already exist in `core/diagnostics/src/test/java/com/poyka/ripdpi/diagnostics/GoldenContractSupport.kt`.

## Coverage Gaps To Close Before Refactor

### Additional Characterization Tests

- manager:
  - `loadSessionDetail` structure and data selection behavior
  - `loadApproachDetail` lookup/filtering behavior
  - `buildShareSummary` redaction and fallback selection behavior
  - `keepResolverRecommendationForSession` no-op and apply behavior
- viewmodel contract:
  - share preview content and target-session wiring
  - selected bottom-sheet state interactions that matter to screen extraction
  - section-level state that should remain stable while screen files move
- screen:
  - section switching visibility
  - empty states in sessions/events
  - share actions disabled while archive is busy
  - event auto-scroll toggle visibility and interaction
  - session detail / event / probe bottom sheet visibility rules beyond audit candidate coverage

### Focused Unit Tests For Future Extractions

- resolver recommendation engine
- DNS target expansion helper
- archive summary builder and archive manifest writer
- share summary builder
- chart interpolation helpers
- any extracted UI-model mapper introduced specifically for this refactor

## Recommended Validation Gates

- targeted manager tests:
  - `./gradlew :core:diagnostics:testDebugUnitTest --tests com.poyka.ripdpi.diagnostics.DiagnosticsManagerTest`
- targeted app tests:
  - `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.activities.DiagnosticsViewModelTest --tests com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsScreenTest`
- targeted screenshot tests when added:
  - `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.ui.screenshot.*`
- final feature gate:
  - `./gradlew testDebugUnitTest`
- final hygiene gate:
  - `./gradlew staticAnalysis`

## Testing Strategy Conclusion

The repository already has a strong diagnostics testing foundation. The refactor plan should extend that coverage rather than invent a new testing approach. The safest pattern is:

1. add missing characterization tests first
2. extract pure logic behind focused unit tests
3. keep manager-contract and screen-contract tests running after every slice
4. use screenshot tests selectively for diagnostics sections where visual structure is part of the contract
