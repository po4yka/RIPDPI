---
name: kotlin-test-patterns
description: Kotlin unit, Compose, Hilt, coroutine, fake, fixture, and golden-test patterns.
---

# Kotlin Test Patterns -- RIPDPI

## 1. Test Module Layout

| Source set | Runner | Purpose |
|---|---|---|
| `app/src/test/` | Robolectric + JUnit 4 | ViewModel, Compose UI, screenshot, pure logic |
| `app/src/androidTest/` | AndroidJUnitRunner + Hilt | Instrumented integration, E2E service lifecycle |
| `core/data/src/test/` | JUnit 4 (no Android) | Data model serialization, settings validation |
| `core/engine/src/test/` | JUnit 4 | Native bridge fakes, golden contract telemetry |
| `core/diagnostics/src/test/` | Robolectric | Scan pipeline, contract governance, archive export |
| `core/service/src/test/` | JUnit 4 | VPN/proxy runtime coordination, policy resolution |

Test deps bundle in `gradle/libs.versions.toml`: `junit`, `kotlinx-coroutines-test`,
`turbine`, `robolectric`. Additional: `androidx-compose-ui-test-junit4`, `roborazzi`, `hilt-android-testing`.

## 2. MainDispatcherRule

Located at `app/src/test/kotlin/com/poyka/ripdpi/util/MainDispatcherRule.kt`.
Calls `Dispatchers.setMain(UnconfinedTestDispatcher())` in `starting()` and
`Dispatchers.resetMain()` in `finished()`. Required in every ViewModel test:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initialize is explicit and idempotent`() = runTest {
        val viewModel = createViewModel(initialize = false)
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.initialize()
        runCurrent()
        // assert...
        collector.cancel()
    }
}
```

## 3. ViewModel Testing Pattern

1. Create fakes for all dependencies (see Section 6).
2. Call `createViewModel(...)` or `createDiagnosticsViewModel(...)` factory.
3. Launch a background collector: `backgroundScope.launch { viewModel.uiState.collect {} }`.
4. Trigger actions, call `advanceUntilIdle()`, assert state.
5. Cancel the collector.

```kotlin
@Test
fun `start with granted permissions starts immediately`() = runTest {
    val serviceController = FakeServiceController()
    val viewModel = createViewModel(
        serviceController = serviceController,
        permissionStatusProvider = FakePermissionStatusProvider(
            snapshot = PermissionSnapshot(
                vpnConsent = PermissionStatus.Granted,
                notifications = PermissionStatus.Granted,
                batteryOptimization = PermissionStatus.Granted,
            ),
        ),
    )
    val collector = backgroundScope.launch { viewModel.uiState.collect {} }
    advanceUntilIdle()
    viewModel.onPrimaryConnectionAction()
    advanceUntilIdle()
    assertEquals(listOf(Mode.VPN), serviceController.startedModes)
    collector.cancel()
}
```

## 4. Flow Testing with Turbine

Used for one-shot effect channels and StateFlow assertion. Key APIs: `test { }`,
`awaitItem()`, `cancelAndIgnoreRemainingEvents()`.

```kotlin
// Effect channel testing (MainViewModelTest)
viewModel.effects.test {
    viewModel.onPrimaryConnectionAction()
    val effect = awaitItem() as MainEffect.RequestPermission
    assertEquals(PermissionKind.Notifications, effect.kind)
    cancelAndIgnoreRemainingEvents()
}

// StateFlow testing (LogsViewModelFlowTest)
vm.uiState.test {
    val state = awaitItem()
    assertTrue(state.logs.isEmpty())
    assertEquals(LogSubsystem.entries.toSet(), state.activeSubsystems)
}
```

## 5. Compose UI Testing

### ComposeTestRule (Robolectric)

Requires `@GraphicsMode(GraphicsMode.Mode.NATIVE)` and `@Config(sdk = [35])`.
Test tags centralized in `com.poyka.ripdpi.ui.testing.RipDpiTestTags`.

```kotlin
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun backgroundGuidanceBanner() {
        composeRule.setContent {
            RipDpiTheme { HomeScreen(uiState = uiStateWithBothBanners(), /* ... */) }
        }
        composeRule.onNodeWithTag(RipDpiTestTags.HomePermissionRecommendationBanner)
            .assertIsDisplayed().assertHasClickAction()
    }
}
```

Common: `assertIsDisplayed()`, `assertDoesNotExist()`, `performClick()`,
`performScrollTo()`, `performTouchInput { swipeUp() }`.

### Roborazzi Screenshot Tests

Helper at `app/src/test/.../ui/screenshot/RipDpiScreenshotTestSupport.kt` configures
`RoborazziOptions(changeThreshold = 0.01F)` and `inspectionMode(true)`.

```kotlin
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RipDpiScreenCatalogScreenshotTest {
    @Test
    fun homeExpandedScreen() {
        captureRipDpiScreenshot(widthDp = 1040, heightDp = 920) {
            RipDpiHomeExpandedPreviewScene()
        }
    }
}
```

Preview scenes are `@Composable` functions defined alongside their screens.

## 6. Test Doubles -- Hand-Rolled Fakes (No Mockk)

The project does NOT use Mockk. All test doubles are hand-rolled fakes with
`MutableStateFlow` fields for state and counters for call verification.

**Naming convention**: `Fake*` for port implementations, `Test*` for service-layer
doubles, `Stub*` for minimal no-ops, `Recording*` for call-counting wrappers.

### Key locations

- **App layer**: `app/src/test/.../activities/TestDoubles.kt` --
  `FakeAppSettingsRepository`, `FakeServiceStateStore`, `FakePermissionStatusProvider`,
  `FakeServiceController`
- **Diagnostics ports**: `app/src/test/.../activities/DiagnosticsTestPorts.kt` --
  `FakeDiagnosticsManager` (aggregates fake bootstrapper, timeline, scan controller,
  detail loader, share service with `MutableStateFlow` properties)
- **Diagnostics stores**: `core/diagnostics/src/test/.../DiagnosticsServiceTestSupport.kt` --
  `FakeDiagnosticsHistoryStores` (in-memory implementation of all record-store interfaces)
- **Engine**: `core/engine/src/test/.../TestDoubles.kt` --
  `FakeRipDpiProxyBindings` (with `CompletableDeferred` blockers and `telemetryJson` field)
- **Service**: `core/service/src/test/.../ServiceControllerTestDoubles.kt` --
  `TestVpnTunnelRuntime`, `TestTun2SocksBridge`, `TestVpnTunnelSession`

### Example fake pattern

```kotlin
class FakeAppSettingsRepository(
    initialSettings: AppSettings = AppSettingsSerializer.defaultValue,
) : AppSettingsRepository {
    private val state = MutableStateFlow(initialSettings)
    override val settings: Flow<AppSettings> = state
    override suspend fun snapshot(): AppSettings = state.value
    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value = state.value.toBuilder().apply(transform).build()
    }
}
```

## 7. Golden Contract Tests

`GoldenContractSupport` verifies serialization stability across Kotlin/Rust boundaries.
Copies exist in `core/engine`, `core/diagnostics`, `core/service`, and `app/src/androidTest`.

**How it works**: serialize to JSON, sort keys, pretty-print, compare against committed
`.json` file in `src/test/resources/golden/`. On mismatch: writes `.expected`, `.actual`,
`.diff` to `build/golden-diffs/`. Update with `RIPDPI_BLESS_GOLDENS=1 ./gradlew test`.

```kotlin
GoldenContractSupport.assertJsonGolden(
    "proxy_running_first_poll.json",
    json.encodeToString(NativeRuntimeSnapshot.serializer(), proxy.pollTelemetry()),
)
```

`DiagnosticsContractGovernanceTest` also verifies shared JSON fixtures decode with
current serializers, schema versions match Rust `wire.rs` constants, and bundled catalog
asset matches committed fixture.

## 8. Hilt Instrumented Test Setup

Tests in `app/src/androidTest/` use `@HiltAndroidTest` with `@UninstallModules` and
`@BindValue` to replace production modules with fakes:

```kotlin
@HiltAndroidTest
@UninstallModules(AppSettingsRepositoryModule::class, RipDpiProxyFactoryModule::class, /* ... */)
class ServiceLifecycleIntegrationTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)
    @get:Rule val permRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @BindValue @JvmField
    var appSettingsRepository: AppSettingsRepository = IntegrationTestOverrides.appSettingsRepository

    @Before fun setUp() {
        IntegrationTestOverrides.reset()
        hiltRule.inject()
    }
}
```

`IntegrationTestOverrides` provides pre-configured fakes with `reset()` for test isolation.

## 9. Diagnostics Test Infrastructure

Factory in `core/diagnostics/src/test/.../DiagnosticsTestBuilders.kt` assembles full
service graphs with sensible defaults:

```kotlin
internal fun createDiagnosticsServices(
    context: Context,
    appSettingsRepository: AppSettingsRepository,
    stores: FakeDiagnosticsHistoryStores,
    // ... many params with defaults
): DiagnosticsServicesBundle
```

Helpers: `diagnosticsTestJson()`, `repoFixture(path)`, `TestDiagnosticsHistoryClock`,
`RecordingArchiveExporter`, `RecordingRuntimeHistoryStartup`.

## 10. Common Mistakes

1. **Forgetting `runTest`**: coroutine tests MUST use `runTest { }` -- without it,
   `advanceUntilIdle()` and `backgroundScope` are unavailable.

2. **Missing `MainDispatcherRule`**: ViewModel tests crash with "Main dispatcher failed
   to initialize" without this rule.

3. **Not collecting `uiState`**: `stateIn(SharingStarted.WhileSubscribed)` flows only
   emit while collected. Always: `backgroundScope.launch { viewModel.uiState.collect {} }`.

4. **Leaking scope**: cancel background collectors at test end.

5. **Using Mockk**: project convention is hand-rolled fakes. Do not introduce mocking
   frameworks. New fakes go in `TestDoubles.kt` or module-specific support files.

6. **Wrong Robolectric config**: Compose tests require `@GraphicsMode(NATIVE)` +
   `@Config(sdk = [35])`.

7. **Golden failures after schema changes**: run
   `RIPDPI_BLESS_GOLDENS=1 ./gradlew :core:engine:test`. Never manually edit golden JSON.

8. **Hilt isolation**: call `IntegrationTestOverrides.reset()` in `@Before` and
   re-assign `@BindValue` fields.
