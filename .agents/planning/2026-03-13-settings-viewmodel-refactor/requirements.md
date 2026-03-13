# Requirements

## Scope

Create a planning-only PDD/TDD refactor for [SettingsViewModel.kt](/Users/po4yka/GitRep/RIPDPI/app/src/main/java/com/poyka/ripdpi/activities/SettingsViewModel.kt). This pass does not implement the refactor. The outcome is a safe extraction plan that shrinks the ViewModel into a state holder and intent coordinator while preserving current settings behavior.

## Verified Current Responsibilities

Code inspection confirms that `SettingsViewModel.kt` currently owns all of the following:

1. Screen contract and state hydration in the same file.
   It declares `SettingsEffect`, `SettingsNoticeTone`, `SettingsUiState`, `HostPackCatalogUiState`, and the large `AppSettings.toUiState(...)` mapper that derives screen state and computed flags.

2. Upstream flow aggregation.
   `uiState` combines `AppSettingsRepository.settings`, `ServiceStateStore.telemetry`, the local host-autolearn refresh trigger, and `RememberedNetworkPolicyStore.observePolicies(limit = 64)`.

3. Generic settings mutation entry points.
   `update(...)` and `updateSetting(...)` are shared editing hooks used heavily by [AdvancedSettingsRoute.kt](/Users/po4yka/GitRep/RIPDPI/app/src/main/java/com/poyka/ripdpi/ui/screens/settings/AdvancedSettingsRoute.kt) and [DnsSettingsScreen.kt](/Users/po4yka/GitRep/RIPDPI/app/src/main/java/com/poyka/ripdpi/ui/screens/dns/DnsSettingsScreen.kt).

4. DNS selection and resolver normalization.
   The ViewModel currently owns built-in provider selection, protocol switching, plain DNS, custom DoH, custom DoT, and custom DNSCrypt writes.

5. Personalization and privacy/security updates.
   It directly handles app theme, launcher icon variant, themed icon style, WebRTC protection, biometrics, and backup PIN.

6. Curated host pack workflows.
   The file loads the initial host pack catalog, applies curated presets, refreshes remote catalog data, and maps refresh errors to notices.

7. Runtime-sensitive cleanup flows.
   It clears learned hosts, clears remembered networks, refreshes host-autolearn store state, and varies user-facing notices based on service status and delete success.

8. Sensitive reset workflows.
   It resets all settings, fake TLS profile, adaptive fake TTL profile, fake payload library, HTTP parser evasions, and activation-window settings.

9. Effect emission and side effects.
   The ViewModel owns the buffered `effects` channel and currently decides when to emit `SettingChanged` versus `Notice` events after repository writes, launcher icon application, refresh outcomes, and cleanup/reset workflows.

## Stable Public Surface That Must Be Preserved

The refactor must preserve the current public surface and consumer expectations:

1. `uiState`, `effects`, and `hostPackCatalog` remain observable flows with the same semantics.
2. `update(...)` and `updateSetting(...)` remain stable because the advanced settings editor uses them extensively.
3. Named public intents remain callable by current screens:
   - DNS methods
   - host pack methods
   - icon/theme methods
   - biometric and backup PIN methods
   - cleanup methods
   - reset methods
4. State-derived flags in `SettingsUiState` keep their current meaning because the settings screens already rely on them.

## Current Safety Net

There is already meaningful mapper coverage in [SettingsUiStateTest.kt](/Users/po4yka/GitRep/RIPDPI/app/src/test/java/com/poyka/ripdpi/activities/SettingsUiStateTest.kt). That suite verifies many computed flags and default normalization rules in `toUiState(...)`.

There is not yet a dedicated `SettingsViewModel` characterization suite. Test infrastructure does exist:

1. [TestDoubles.kt](/Users/po4yka/GitRep/RIPDPI/app/src/test/java/com/poyka/ripdpi/activities/TestDoubles.kt) already provides fake `AppSettingsRepository`, `ServiceStateStore`, and `LauncherIconController`.
2. [MainDispatcherRule.kt](/Users/po4yka/GitRep/RIPDPI/app/src/test/java/com/poyka/ripdpi/util/MainDispatcherRule.kt) and Turbine patterns are already established in other ViewModel tests.
3. Robolectric is already used in app-level ViewModel tests when Android `Context` behavior is required.

## Characterization Gaps To Close Before Structural Changes

The refactor plan must add explicit `SettingsViewModel` characterization coverage before moving the related logic:

1. Initial `uiState` hydration from repository settings, runtime telemetry, remembered-network count, and host-autolearn store presence.
2. `effects` emission for `updateSetting(...)` and for each named public intent that currently emits `SettingChanged` or `Notice`.
3. DNS behavior:
   - built-in provider selection
   - encrypted protocol switching cleanup
   - custom DoH host and port derivation
   - custom DoT and DNSCrypt normalization
   - invalid built-in provider no-op behavior
4. Host pack behavior:
   - initial catalog load
   - preset application
   - refresh success path
   - refresh rollback plus message mapping for checksum, parse/build, and generic failures
5. Icon behavior:
   - icon key normalization
   - themed icon toggle semantics
   - repository write plus `LauncherIconController.applySelection(...)`
6. Privacy and security behavior:
   - WebRTC toggle
   - biometric toggle
   - backup PIN save
7. Cleanup behavior:
   - learned-host deletion success and failure
   - running versus halted notice text for learned-host cleanup
   - remembered-network clearing
8. Reset behavior:
   - full settings reset
   - fake TLS reset
   - adaptive fake TTL reset
   - fake payload library reset
   - HTTP parser evasion reset
   - activation-window reset
   - running versus halted notice text for sensitive reset flows
9. State emission after collaborator-owned writes.
   The refactor must prove that `uiState` still reflects repository updates and refresh triggers in the same way after extraction.

## Required Outcome

The refactor is complete only when all of the following are true:

1. `SettingsViewModel` stays the cohesive state holder and intent coordinator.
   It owns flow wiring, public entry points, refresh triggers, and effect publishing.

2. Unrelated domains move behind explicit collaborators.
   DNS, host pack operations, appearance, privacy/security, and maintenance/reset logic must be extracted into clearly named concrete classes or use cases with focused responsibilities.

3. State emissions remain stable.
   The same repository writes and runtime inputs must produce the same visible `SettingsUiState` values and `HostPackCatalogUiState` transitions.

4. Side effects remain stable.
   The same actions must still apply launcher icon changes, refresh host packs, clear stores, and emit the same user-visible notice or setting-change effects.

5. Generic editing hooks remain available.
   `update(...)` and `updateSetting(...)` stay in the ViewModel as the low-level write path for the advanced settings editor instead of being pushed into a vague generic abstraction.

6. The design stays local to the target file.
   It may add supporting collaborators and tests in nearby packages, but it must not overwrite or depend on the other active refactor documentation under `.agents/planning/`.

## Boundary Requirements

The target architecture must enforce these boundaries:

1. ViewModel boundary.
   Keeps only state aggregation, refresh triggers, public intents, coroutine launching, and effect publishing.

2. State-mapper boundary.
   `AppSettings` plus runtime inputs map into `SettingsUiState` through a pure collaborator or file split. It must not own repository writes or effect emission.

3. Domain delegate boundary.
   Each extracted domain class receives only the dependencies it actually needs and exposes domain-specific methods. Do not introduce a catch-all `SettingsManager`.

4. Android file-system boundary.
   Direct calls to `hasHostAutolearnStore(...)` and `clearHostAutolearnStore(...)` should move behind an explicit gateway so cleanup behavior is testable without leaving Android file APIs embedded in the ViewModel.

5. Message boundary.
   Notice copy and `SettingChanged` keys/values remain explicit and domain-owned. Avoid generic “command” layers that hide which setting changed.

## Testing Requirements

1. Characterization tests first.
   Add or tighten `SettingsViewModel` tests before extracting the domain they protect.

2. Unit tests for every new collaborator.
   DNS, host packs, appearance, privacy/security, maintenance/reset logic, and state mapping each receive direct unit coverage.

3. Coroutine and Flow verification.
   Use coroutine test utilities plus Turbine to verify `uiState`, `hostPackCatalog`, and `effects`.

4. Regression coverage for sensitive flows.
   Security, reset, cleanup, and host pack refresh flows keep exact coverage for message tone and running-versus-halted behavior.

5. No safety-net regression.
   `SettingsUiStateTest` remains intact as the guardrail for derived-state behavior while the new ViewModel suite protects public intent behavior.

## Non-Goals

1. No UI redesign or navigation changes.
2. No broad rewrite of the advanced settings screen callbacks.
3. No repository or protobuf schema redesign.
4. No implementation in this planning pass.
