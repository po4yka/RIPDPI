# Implementation Plan

## Checklist

- [ ] Step 1: Build a full `SettingsViewModel` characterization suite before moving production code
- [ ] Step 2: Extract the contract split, typed runtime snapshot, and UI-state mapper
- [ ] Step 3: Introduce the host-autolearn store gateway seam under test coverage
- [ ] Step 4: Extract DNS behavior into a dedicated collaborator
- [ ] Step 5: Extract host pack catalog and curated preset workflows
- [ ] Step 6: Extract appearance plus privacy/security collaborators
- [ ] Step 7: Extract maintenance, cleanup, and reset workflows
- [ ] Step 8: Slim `SettingsViewModel` to orchestration and verify the final dependency graph

## Plan

1. Step 1: Build a full `SettingsViewModel` characterization suite before moving production code
   Objective: Turn current public ViewModel behavior into an executable spec before any extraction starts.
   Guidance: Add `SettingsViewModelTest` alongside the existing activities tests. Reuse `FakeAppSettingsRepository`, `FakeServiceStateStore`, and `FakeLauncherIconController`, and add focused fakes for `RememberedNetworkPolicyStore` and `HostPackCatalogRepository`. Use Turbine to observe `uiState`, `hostPackCatalog`, and `effects`. Cover initial hydration, `updateSetting(...)`, all DNS methods, host pack preset application, refresh success and failure variants, icon changes, WebRTC, biometrics, backup PIN, remembered-network clearing, learned-host clearing, full reset, and every profile-reset flow.
   Test requirements: Tests must assert only public outcomes: repository state, `uiState`, `hostPackCatalog`, side-effect controller calls, and emitted `SettingsEffect` instances. For learned-host cleanup, use a real Robolectric `Context` and actual host-autolearn test files until the gateway seam exists.
   Integration: This is a tests-first step. Production code changes should be limited to tiny visibility adjustments only if a test cannot otherwise observe current behavior.
   Demo: The app behavior is unchanged, but the new suite fails on any regression in settings state, effect emission, host pack refresh messaging, or cleanup/reset semantics.

2. Step 2: Extract the contract split, typed runtime snapshot, and UI-state mapper
   Objective: Remove low-risk structural weight first while preserving all visible behavior.
   Guidance: Move `SettingsEffect`, `SettingsNoticeTone`, `SettingsUiState`, and `HostPackCatalogUiState` into a dedicated contract file if that reduces file pressure cleanly. Introduce `SettingsRuntimeSnapshot` and move `AppSettings.toUiState(...)` into `SettingsUiStateMapper` or an equivalent pure file split. Keep the `combine(...)` block in the ViewModel, but make it construct typed mapper input instead of embedding mapping details inline.
   Test requirements: Keep the existing [SettingsUiStateTest.kt](/Users/po4yka/GitRep/RIPDPI/app/src/test/java/com/poyka/ripdpi/activities/SettingsUiStateTest.kt) green and run the new `SettingsViewModelTest` unchanged. Add only narrow direct mapper tests if the moved API needs extra coverage.
   Integration: The ViewModel still exposes the same flows and public methods. Only the state-mapping implementation moves.
   Demo: `SettingsViewModel.kt` becomes materially smaller, and all derived-state behavior still matches the previous screen output.

3. Step 3: Introduce the host-autolearn store gateway seam under test coverage
   Objective: Remove direct Android filesystem helpers from the ViewModel before extracting cleanup logic.
   Guidance: Add a small `HostAutolearnStoreGateway` that wraps store presence checks and delete behavior. Replace direct calls to `hasHostAutolearnStore(...)` and `clearHostAutolearnStore(...)` with the gateway. Keep the gateway concrete unless an interface is needed for tests.
   Test requirements: First extend the characterization suite so initial hydration and `forgetLearnedHosts()` behavior are already protected. Then add direct tests for the gateway only if it contains more than simple forwarding. Finally update the ViewModel tests to fake the gateway instead of touching files where appropriate.
   Integration: This is an internal seam change only. It should not alter user-visible copy, tone, or store-refresh timing.
   Demo: Learned-host state still appears the same in `uiState`, and learned-host cleanup still emits the same notices, but the ViewModel no longer performs filesystem checks directly.

4. Step 4: Extract DNS behavior into a dedicated collaborator
   Objective: Move the most self-contained nontrivial domain out of the ViewModel early.
   Guidance: Create `DnsSettingsDelegate` with explicit methods matching the current public DNS intents. Keep repository writes, normalization, and protocol-specific field cleanup inside that collaborator. Let the ViewModel keep method names, coroutine launching, and effect publication while delegating the actual write behavior.
   Test requirements: Before the extraction, ensure Step 1 already covers built-in provider selection, protocol switching cleanup, plain DNS selection, custom DoH host and port derivation, custom DoT normalization, custom DNSCrypt normalization, and invalid provider no-op behavior. After extraction, add direct unit tests for `DnsSettingsDelegate` that assert repository writes and returned effect metadata.
   Integration: `DnsSettingsScreen` and current call sites remain untouched because public ViewModel method names stay stable.
   Demo: DNS selection works exactly as before from the screen’s perspective, while the ViewModel loses a large domain-specific block of repository mutation code.

5. Step 5: Extract host pack catalog and curated preset workflows
   Objective: Isolate the remote-refresh and curated-host logic from the generic settings coordinator role of the ViewModel.
   Guidance: Move initial snapshot loading, curated preset application support, and refresh-result mapping into `HostPackSettingsDelegate`. Keep `hostPackCatalogState` in the ViewModel so the screen still collects the same `StateFlow`. The delegate should return explicit host-pack outcomes that include the snapshot to publish and the notice to emit.
   Test requirements: Characterization must already cover initial snapshot load, preset application, refresh-in-progress toggling, refresh success, refresh rollback on failure, and the three current failure-message families. Add direct unit tests for the delegate’s result mapping and preset-application behavior.
   Integration: The ViewModel still owns `hostPackCatalog`, but remote host pack behavior becomes an isolated domain with its own tests.
   Demo: The host pack section still shows the same refresh spinner, same snapshot rollback behavior, and same notice copy, but the ViewModel no longer embeds error classification logic.

6. Step 6: Extract appearance plus privacy/security collaborators
   Objective: Remove the UI-personalization and sensitive-preference writes that do not belong beside DNS or maintenance logic.
   Guidance: Extract `AppearanceSettingsDelegate` for theme and launcher icon behavior, and `PrivacySecuritySettingsDelegate` for WebRTC protection, biometrics, and backup PIN. Keep the ViewModel responsible for launching coroutines and emitting `SettingChanged` effects, but move repository writes, key normalization, and launcher icon side-effect preparation into the collaborators.
   Test requirements: Ensure the characterization suite already covers icon-key normalization, themed icon behavior, `LauncherIconController.applySelection(...)`, theme saves, WebRTC toggles, biometric toggles, and backup PIN saves. Add collaborator tests for exact repository changes and icon-selection inputs.
   Integration: [AppCustomizationScreen.kt](/Users/po4yka/GitRep/RIPDPI/app/src/main/java/com/poyka/ripdpi/ui/screens/customization/AppCustomizationScreen.kt) and [SettingsPreferencesScreen.kt](/Users/po4yka/GitRep/RIPDPI/app/src/main/java/com/poyka/ripdpi/ui/screens/settings/SettingsPreferencesScreen.kt) keep using the same ViewModel methods.
   Demo: Appearance, privacy, and security settings behave exactly as before, and the ViewModel loses another unrelated set of imperative writes and side effects.

7. Step 7: Extract maintenance, cleanup, and reset workflows
   Objective: Move the most sensitive service-aware flows behind one explicit maintenance domain boundary.
   Guidance: Extract `MaintenanceSettingsDelegate` for learned-host cleanup, remembered-network clearing, full reset, fake TLS reset, adaptive fake TTL reset, fake payload reset, HTTP parser reset, and activation-window reset. Keep the collaborator focused on repository/store interaction plus service-aware notice selection. Do not fold DNS or host pack logic into this class.
   Test requirements: Before moving this logic, the characterization suite must already lock down running-versus-halted notice text for every reset flow, learned-host delete failure behavior, remembered-network clearing, and full reset effect emission. After extraction, add direct unit tests for each maintenance branch so service-status-dependent messages are not verified only through the ViewModel.
   Integration: The ViewModel still triggers host-autolearn refresh counters and emits effects, but no longer owns the field-by-field reset logic or service-aware message selection.
   Demo: Cleanup and reset actions keep the same behavior and user messaging, while the ViewModel drops its largest block of sensitive branch-heavy maintenance code.

8. Step 8: Slim `SettingsViewModel` to orchestration and verify the final dependency graph
   Objective: Finish the refactor by leaving only the responsibilities that truly belong in the ViewModel.
   Guidance: Remove obsolete private helpers and inline mutation blocks that were replaced by collaborators. Keep `update(...)`, `updateSetting(...)`, flow aggregation, refresh triggers, and effect publishing local. Review constructor dependencies to ensure each collaborator is explicit and domain-focused. Avoid introducing a new generic façade to hide the extracted classes.
   Test requirements: Run the full `SettingsUiStateTest` suite, the full `SettingsViewModelTest` characterization suite, and all new collaborator unit tests. Add one final smoke test if needed to confirm that all extracted collaborators still produce the same end-to-end `uiState` and `effects` behavior together.
   Integration: This is the final cleanup step. UI call sites and route binding should not need to change.
   Demo: `SettingsViewModel.kt` reads as a coordinator instead of a monolith, with all settings flows preserved and every major move protected by tests that were written first.
