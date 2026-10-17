package com.poyka.ripdpi.activities

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.ui.screens.diagnostics.DiagnosticsRoute
import com.poyka.ripdpi.ui.testing.RipDpiTestTags
import com.poyka.ripdpi.ui.theme.RipDpiTheme
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DiagnosticsHapticEligibilityTest {
    @get:Rule val composeRule = createComposeRule()

    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private var previousStaticMotion: String? = null

    @Before
    fun enableMotion() {
        previousStaticMotion = System.getProperty("ripdpi.staticMotion")
        System.setProperty("ripdpi.staticMotion", "false")
    }

    @After
    fun restoreMotion() {
        previousStaticMotion?.let { System.setProperty("ripdpi.staticMotion", it) }
            ?: System.clearProperty("ripdpi.staticMotion")
    }

    @Test
    fun `completion generated while Off stays quiet after System is enabled before resume`() =
        runTest {
            val settings = disabledSettings()
            val manager = FakeDiagnosticsManager()
            val viewModel = createDiagnosticsViewModel(diagnosticsManager = manager, appSettingsRepository = settings)
            val owner = DiagnosticsLifecycleOwner()
            val disabled = mutableStateOf(true)
            val feedback = mutableListOf<Int>()
            setRouteContent(viewModel, owner, disabled, feedback)
            runCurrent()

            completeScan(manager, "completed-while-off")
            assertTrue(feedback.isEmpty())
            settings.update { setHapticFeedbackDisabled(false) }
            composeRule.runOnIdle { disabled.value = false }
            composeRule.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStatusSnackbar).assertIsDisplayed()
            composeRule.runOnIdle { assertTrue(feedback.isEmpty()) }

            composeRule.runOnIdle { owner.moveTo(Lifecycle.State.CREATED) }
            composeRule.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
            composeRule.runOnIdle { assertTrue(feedback.isEmpty()) }
            completeScan(manager, "completed-after-enabling")
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(1, feedback.size) }
        }

    @Test
    fun `start failure generated while Off retains its message without a late error haptic`() =
        runTest {
            val settings = disabledSettings()
            val failedStart = CompletableDeferred<DiagnosticsManualScanStartResult>()
            failedStart.completeExceptionally(IOException("Start unavailable"))
            var startCalls = 0
            val manager =
                FakeDiagnosticsManager().apply {
                    scanController.onStartScan = { _, _ ->
                        startCalls += 1
                        failedStart.await()
                    }
                }
            val viewModel = createDiagnosticsViewModel(diagnosticsManager = manager, appSettingsRepository = settings)
            val owner = DiagnosticsLifecycleOwner()
            val disabled = mutableStateOf(true)
            val feedback = mutableListOf<Int>()
            setRouteContent(viewModel, owner, disabled, feedback)
            runCurrent()

            viewModel.startRawScan()
            runCurrent()
            assertEquals(1, startCalls)
            assertTrue(feedback.isEmpty())
            settings.update { setHapticFeedbackDisabled(false) }
            composeRule.runOnIdle { disabled.value = false }
            composeRule.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStatusSnackbar).assertIsDisplayed()
            composeRule.runOnIdle { assertTrue(feedback.isEmpty()) }

            composeRule.runOnIdle { owner.moveTo(Lifecycle.State.CREATED) }
            composeRule.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
            composeRule.runOnIdle { assertTrue(feedback.isEmpty()) }
            viewModel.startRawScan()
            runCurrent()
            assertEquals(2, startCalls)
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(1, feedback.size) }
        }

    @Test
    fun `accepted start generated while Off does not acknowledge after feedback is enabled`() =
        runTest {
            val settings = disabledSettings()
            var startCalls = 0
            val manager =
                FakeDiagnosticsManager().apply {
                    scanController.onStartScan = { _, _ ->
                        startCalls += 1
                        DiagnosticsManualScanStartResult.Started("accepted-while-off")
                    }
                }
            val viewModel = createDiagnosticsViewModel(diagnosticsManager = manager, appSettingsRepository = settings)
            val owner = DiagnosticsLifecycleOwner()
            val disabled = mutableStateOf(true)
            val feedback = mutableListOf<Int>()
            setRouteContent(viewModel, owner, disabled, feedback)
            runCurrent()

            viewModel.startRawScan()
            runCurrent()
            assertEquals(1, startCalls)
            assertTrue(feedback.isEmpty())
            settings.update { setHapticFeedbackDisabled(false) }
            composeRule.runOnIdle { disabled.value = false }
            composeRule.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStatusSnackbar).assertIsDisplayed()
            composeRule.runOnIdle { assertTrue(feedback.isEmpty()) }
        }

    @Test
    fun `accepted start in System shows information without a result haptic`() =
        runTest {
            val settings = FakeDiagnosticsHapticSettings()
            var startCalls = 0
            val manager =
                FakeDiagnosticsManager().apply {
                    scanController.onStartScan = { _, _ ->
                        startCalls += 1
                        DiagnosticsManualScanStartResult.Started("system-accepted")
                    }
                }
            val viewModel = createDiagnosticsViewModel(diagnosticsManager = manager, appSettingsRepository = settings)
            val owner = DiagnosticsLifecycleOwner()
            val disabled = mutableStateOf(false)
            val feedback = mutableListOf<Int>()
            setRouteContent(viewModel, owner, disabled, feedback)
            runCurrent()

            val readsBeforeStart = settings.snapshotReads
            viewModel.startRawScan()
            runCurrent()
            assertEquals(1, startCalls)
            assertEquals(readsBeforeStart, settings.snapshotReads)
            composeRule.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStatusSnackbar).assertIsDisplayed()
            composeRule.runOnIdle { assertTrue(feedback.isEmpty()) }
        }

    @Test
    fun `eligibility IO failure preserves the completion message and a later result still signals`() =
        runTest {
            val settings = FakeDiagnosticsHapticSettings()
            val manager = FakeDiagnosticsManager()
            val viewModel = createDiagnosticsViewModel(diagnosticsManager = manager, appSettingsRepository = settings)
            val owner = DiagnosticsLifecycleOwner()
            val feedback = mutableListOf<Int>()
            setRouteContent(viewModel, owner, mutableStateOf(false), feedback)
            runCurrent()
            val readsBeforeCompletion = settings.snapshotReads
            settings.nextSnapshotFailure = IOException("Preference storage unavailable")

            completeScan(manager, "eligibility-read-failed")
            assertEquals(readsBeforeCompletion + 1, settings.snapshotReads)
            assertEquals(null, settings.nextSnapshotFailure)
            composeRule.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStatusSnackbar).assertIsDisplayed()
            composeRule.runOnIdle { assertTrue(feedback.isEmpty()) }

            composeRule.runOnIdle { owner.moveTo(Lifecycle.State.CREATED) }
            composeRule.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
            completeScan(manager, "eligibility-read-recovered")
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(1, feedback.size) }
        }

    @Test
    fun `eligibility cancellation is not converted to a visible completion or a haptic`() =
        runTest {
            val settings = FakeDiagnosticsHapticSettings()
            val manager = FakeDiagnosticsManager()
            val viewModel = createDiagnosticsViewModel(diagnosticsManager = manager, appSettingsRepository = settings)
            val owner = DiagnosticsLifecycleOwner()
            val feedback = mutableListOf<Int>()
            setRouteContent(viewModel, owner, mutableStateOf(false), feedback)
            runCurrent()
            val readsBeforeCompletion = settings.snapshotReads
            settings.nextSnapshotFailure = CancellationException("Eligibility read cancelled")

            completeScan(manager, "eligibility-read-cancelled")
            assertEquals(readsBeforeCompletion + 1, settings.snapshotReads)
            assertEquals(null, settings.nextSnapshotFailure)
            composeRule.runOnIdle { owner.moveTo(Lifecycle.State.RESUMED) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(RipDpiTestTags.DiagnosticsStatusSnackbar).assertDoesNotExist()
            composeRule.runOnIdle { assertTrue(feedback.isEmpty()) }
        }

    private fun disabledSettings(): FakeAppSettingsRepository =
        FakeAppSettingsRepository(
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setHapticFeedbackDisabled(true)
                .build(),
        )

    private fun setRouteContent(
        viewModel: DiagnosticsViewModel,
        owner: DiagnosticsLifecycleOwner,
        disabled: MutableState<Boolean>,
        feedback: MutableList<Int>,
    ) {
        val recordingView =
            object : View(RuntimeEnvironment.getApplication()) {
                override fun performHapticFeedback(feedbackConstant: Int): Boolean {
                    feedback += feedbackConstant
                    return true
                }
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalView provides recordingView) {
                RipDpiTheme(hapticFeedbackDisabled = disabled.value) {
                    DiagnosticsRoute(viewModel = viewModel)
                }
            }
        }
    }

    private fun TestScope.completeScan(
        manager: FakeDiagnosticsManager,
        sessionId: String,
    ) {
        manager.progressState.value = ScanProgress(sessionId, "https", 0, 1, "Checking")
        runCurrent()
        manager.sessionsState.value = listOf(historyScanSession(id = sessionId, summary = "Check complete"))
        runCurrent()
        manager.progressState.value = null
        runCurrent()
    }
}

private class DiagnosticsLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

private class FakeDiagnosticsHapticSettings(
    private val delegate: FakeAppSettingsRepository = FakeAppSettingsRepository(),
) : AppSettingsRepository by delegate {
    var nextSnapshotFailure: Exception? = null
    var snapshotReads = 0
        private set

    override suspend fun snapshot(): AppSettings {
        snapshotReads += 1
        val failure = nextSnapshotFailure
        nextSnapshotFailure = null
        if (failure != null) throw failure
        return delegate.snapshot()
    }
}
