package com.poyka.ripdpi.ui.screens.diagnostics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.replay.ProbeReplayService
import com.poyka.ripdpi.diagnostics.replay.ReplayProbeRequest
import com.poyka.ripdpi.diagnostics.replay.ReplayResultStore
import com.poyka.ripdpi.diagnostics.replay.ReplayStepEvent
import com.poyka.ripdpi.diagnostics.replay.ReplayVerdict
import com.poyka.ripdpi.platform.LocalizedTimestampFormatter
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression coverage for UIX-1786264762917972: the replay-failure route's
 * auto-start effect must re-run when its probe target (the data-determining
 * session key) changes while the composition stays alive.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReplayFailureRouteAutoStartTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val formatter = LocalizedTimestampFormatter(RuntimeEnvironment.getApplication())
    private val serviceStateStore = FakeServiceStateStore()

    private fun newViewModel(
        service: ProbeReplayService,
        store: ReplayResultStore = ReplayResultStore(),
    ) = ReplayFailureViewModel(service, store, FakeStringResolver(), formatter, serviceStateStore)

    private class RecordingProbeReplayService : ProbeReplayService {
        val requests = mutableListOf<ReplayProbeRequest>()

        override fun run(request: ReplayProbeRequest): Flow<ReplayStepEvent> {
            requests += request
            return flowOf(
                ReplayStepEvent.Finished(
                    verdict = ReplayVerdict.Success,
                    terminalStep = null,
                    recommendationKey = "",
                ),
            )
        }
    }

    private class SuspendedFirstProbeReplayService : ProbeReplayService {
        val requests = mutableListOf<ReplayProbeRequest>()
        val firstCancellationStarted = CompletableDeferred<Unit>()
        val releaseFirstCancellation = CompletableDeferred<Unit>()

        override fun run(request: ReplayProbeRequest): Flow<ReplayStepEvent> {
            requests += request
            return if (requests.size == 1) {
                flow {
                    try {
                        awaitCancellation()
                    } finally {
                        firstCancellationStarted.complete(Unit)
                        withContext(NonCancellable) {
                            releaseFirstCancellation.await()
                        }
                    }
                }
            } else {
                flowOf(
                    ReplayStepEvent.Finished(
                        verdict = ReplayVerdict.Success,
                        terminalStep = null,
                        recommendationKey = "",
                    ),
                )
            }
        }
    }

    private class FakeStringResolver : StringResolver {
        override fun getQuantityString(
            resId: Int,
            quantity: Int,
            vararg formatArgs: Any,
        ): String =
            org.robolectric.RuntimeEnvironment
                .getApplication()
                .resources
                .getQuantityString(resId, quantity, *formatArgs)

        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = resId.toString()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `target switch records cancelled old attempt and successful new attempt`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val service = SuspendedFirstProbeReplayService()
                val store = ReplayResultStore()
                val viewModel = newViewModel(service, store)

                viewModel.ensureStarted(domain = "a.example", strategyId = "s1")
                runCurrent()

                viewModel.ensureStarted(domain = "b.example", strategyId = "s1")
                runCurrent()

                // The new run waits for the old run to finish releasing the strategy guard.
                service.firstCancellationStarted.await()
                assertEquals(listOf("a.example"), service.requests.map { it.domain })
                assertEquals(0, store.recent().size)

                service.releaseFirstCancellation.complete(Unit)
                runCurrent()

                assertEquals(listOf("a.example", "b.example"), service.requests.map { it.domain })
                val resultsByDomain = store.recent().associateBy { it.request.domain }
                assertEquals(2, resultsByDomain.size)
                assertEquals(ReplayVerdict.Cancelled, resultsByDomain.getValue("a.example").verdict)
                assertEquals(ReplayVerdict.Success, resultsByDomain.getValue("b.example").verdict)
            } finally {
                Dispatchers.setMain(UnconfinedTestDispatcher())
            }
        }

    @Test
    fun `changing probe target restarts auto start`() {
        val service = RecordingProbeReplayService()
        val viewModel = newViewModel(service)
        var domain by mutableStateOf("a.example")

        composeRule.setContent {
            ReplayFailureRoute(
                domain = domain,
                onBack = {},
                viewModel = viewModel,
            )
        }
        composeRule.waitForIdle()

        assertEquals(listOf("a.example"), service.requests.map { it.domain })

        composeRule.runOnUiThread { domain = "b.example" }
        composeRule.waitForIdle()

        assertEquals(listOf("a.example", "b.example"), service.requests.map { it.domain })
    }

    @Test
    fun `ensure started skips retained target and starts new target`() {
        val service = RecordingProbeReplayService()
        val viewModel = newViewModel(service)

        viewModel.ensureStarted(domain = "a.example", strategyId = "s1")
        viewModel.ensureStarted(domain = "a.example", strategyId = "s1")
        assertEquals(listOf("a.example"), service.requests.map { it.domain })

        viewModel.ensureStarted(domain = "b.example", strategyId = "s1")
        assertEquals(listOf("a.example", "b.example"), service.requests.map { it.domain })

        viewModel.ensureStarted(domain = "b.example", strategyId = "s2")
        assertEquals(
            listOf("a.example", "b.example", "b.example"),
            service.requests.map { it.domain },
        )
        assertEquals("s2", service.requests.last().strategyId)
    }

    @Test
    fun `running session asks for confirmation before the replay starts`() {
        val service = RecordingProbeReplayService()
        serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
        val viewModel = newViewModel(service)

        viewModel.ensureStarted(domain = "a.example", strategyId = "s1")
        viewModel.ensureStarted(domain = "a.example", strategyId = "s1")

        assertEquals(emptyList<String>(), service.requests.map { it.domain })
        assertEquals(true, viewModel.uiState.value.confirmationRequired)

        viewModel.confirmDisruption()

        assertEquals(listOf("a.example"), service.requests.map { it.domain })
        assertEquals(false, viewModel.uiState.value.confirmationRequired)
    }

    @Test
    fun `dismissed confirmation never starts the replay`() {
        val service = RecordingProbeReplayService()
        serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
        val viewModel = newViewModel(service)

        viewModel.ensureStarted(domain = "a.example", strategyId = "s1")
        viewModel.dismissDisruption()

        assertEquals(emptyList<String>(), service.requests.map { it.domain })
        assertEquals(false, viewModel.uiState.value.confirmationRequired)
    }
}
