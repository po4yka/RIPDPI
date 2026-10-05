package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.diagnostics.DiagnosticEvent
import com.poyka.ripdpi.platform.AndroidStringResolver
import com.poyka.ripdpi.testsupport.FakeServiceStateStore
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsUiStateThreadingTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun `real projection uses injected default and parent cancellation stops all collectors`() =
        runTest {
            val manager = FakeDiagnosticsManager()
            val marked = ThreadLocal.withInitial { false }
            val dispatches = mutableListOf<Boolean>()
            val publications = mutableListOf<Boolean>()
            val dispatcher = MarkedDispatcher(StandardTestDispatcher(testScheduler), marked)
            val formatter =
                object : DiagnosticsUiFormatter() {
                    override fun formatTimestamp(timestamp: Long): String {
                        dispatches += marked.get()
                        return super.formatTimestamp(timestamp)
                    }
                }
            val parent = Job(backgroundScope.coroutineContext[Job])
            val parentScope = CoroutineScope(backgroundScope.coroutineContext + parent)
            val states =
                assembler(formatter, dispatcher).assemble(
                    scope = parentScope,
                    interactionDependencies =
                        DiagnosticsInteractionDependencies(
                            manager.timelineSource,
                            manager.scanController,
                            manager.detailLoader,
                            manager.resolverActions,
                        ),
                    contextDependencies =
                        DiagnosticsContextDependencies(
                            FakeAppSettingsRepository(),
                            EmptyRememberedNetworkPolicySource(),
                            EmptyActiveConnectionPolicySource(),
                            FakeServiceStateStore(),
                        ),
                    selectionState = MutableStateFlow(SelectionState()),
                    filterState = MutableStateFlow(FilterState()),
                    sessionDetailState = MutableStateFlow(SessionDetailState()),
                    scanLifecycleState = MutableStateFlow(ScanLifecycleState()),
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { states.screen.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                states.full.drop(1).collect { publications += marked.get() }
            }
            manager.nativeEventsState.value = events("first")
            runCurrent()
            assertEquals(250, states.full.value.events.events.size)
            assertTrue("Real event projection did not run", dispatches.isNotEmpty())
            assertTrue("Projection escaped injected Default ownership", dispatches.all { it })
            assertTrue("No projected state was published", publications.isNotEmpty())
            assertTrue("State publication escaped injected Default ownership", publications.all { it })
            val sequence =
                states.full.value.performance
                    ?.buildSequence
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { states.full.collect {} }
            runCurrent()
            assertEquals(
                "Second subscriber rebuilt the factory",
                sequence,
                states.full.value.performance
                    ?.buildSequence,
            )
            parent.cancel()
            runCurrent()
            val before = dispatches.size
            manager.nativeEventsState.value = events("after cancellation")
            runCurrent()
            assertEquals(before, dispatches.size)
            assertEquals(
                "first",
                states.full.value.events.events
                    .last()
                    .message,
            )
        }

    private fun assembler(
        formatter: DiagnosticsUiFormatter,
        dispatcher: CoroutineDispatcher,
    ): DiagnosticsUiStateAssembler {
        val strings = AndroidStringResolver(RuntimeEnvironment.getApplication())
        val support = DiagnosticsUiFactorySupport(strings, DiagnosticsUiCoreSupport(formatter, strings))
        val factory =
            DiagnosticsUiStateFactory(
                support,
                DiagnosticsSessionDetailUiFactory(support),
                DiagnosticsUiInputResolver(support),
                DiagnosticsOverviewUiStateFactory(support),
                DiagnosticsScanUiStateFactory(support),
                DiagnosticsLiveUiStateFactory(support),
                DiagnosticsSessionsUiStateFactory(support),
                DiagnosticsApproachesUiStateFactory(support),
                DiagnosticsEventsUiStateFactory(support),
                DiagnosticsShareUiStateFactory(support),
                DiagnosticsPerformanceUiStateFactory(),
            )
        return DiagnosticsUiStateAssembler(factory, AppCoroutineDispatchers(dispatcher, dispatcher, dispatcher))
    }

    private fun events(message: String) =
        (0 until 250).map {
            DiagnosticEvent("event-$it", null, source = "test", level = "info", message = message, createdAt = 1)
        }
}

private class MarkedDispatcher(
    private val delegate: CoroutineDispatcher,
    private val marked: ThreadLocal<Boolean>,
) : CoroutineDispatcher() {
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        delegate.dispatch(context) {
            val previous = marked.get()
            marked.set(true)
            try {
                block.run()
            } finally {
                marked.set(previous)
            }
        }
    }
}
