package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.services.WarpProfileSetupService
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WarpEnrollmentViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `registration stays busy until setup succeeds`() =
        runTest {
            val completion = CompletableDeferred<Unit>()
            val viewModel = WarpEnrollmentViewModel(TestSetupService(register = { completion.await() }))

            assertEquals(WarpEnrollmentUiState(), viewModel.state.value)
            viewModel.register("WARP")
            assertEquals(WarpSetupOperation.Registration, viewModel.state.value.operation)
            assertFalse(viewModel.state.value.saved)
            completion.complete(Unit)
            runCurrent()

            assertEquals(WarpEnrollmentUiState(saved = true), viewModel.state.value)
        }

    @Test
    fun `provider errors expose a fixed failure state without private response data`() =
        runTest {
            val viewModel =
                WarpEnrollmentViewModel(
                    TestSetupService(register = { error("synthetic-private-provider-response") }),
                )

            viewModel.register("WARP")
            runCurrent()

            assertEquals(WarpEnrollmentUiState(failure = WarpSetupFailure.Registration), viewModel.state.value)
            assertFalse(
                viewModel.state.value
                    .toString()
                    .contains("synthetic-private-provider-response"),
            )
        }

    @Test
    fun `unreadable document reports import failure without reporting saved`() =
        runTest {
            val viewModel = WarpEnrollmentViewModel(TestSetupService())

            viewModel.importProfile { null }
            runCurrent()

            assertEquals(WarpEnrollmentUiState(failure = WarpSetupFailure.Import), viewModel.state.value)
        }

    @Test
    fun `cancellation waits for blocking work and never reports that registration was undone`() =
        runTest {
            val completion = CompletableDeferred<Unit>()
            val viewModel =
                WarpEnrollmentViewModel(
                    TestSetupService(register = { withContext(NonCancellable) { completion.await() } }),
                )
            viewModel.register("WARP")
            viewModel.cancel()
            runCurrent()

            assertTrue(viewModel.state.value.busy)
            assertTrue(viewModel.state.value.cancellationRequested)
            assertFalse(viewModel.state.value.saved)
            completion.complete(Unit)
            runCurrent()

            assertEquals(WarpEnrollmentUiState(), viewModel.state.value)
        }

    private class TestSetupService(
        private val register: suspend () -> Unit = {},
    ) : WarpProfileSetupService {
        override suspend fun registerConsumerFree(displayName: String) = register()

        override suspend fun importZeroTrustProfile(document: String) = Unit
    }
}
