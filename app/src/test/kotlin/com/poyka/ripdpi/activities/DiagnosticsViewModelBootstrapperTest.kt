package com.poyka.ripdpi.activities

import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelBootstrapperTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `initialize launches bootstrapper and scan initialization`() =
        runTest {
            val diagnosticsBootstrapper = StubDiagnosticsBootstrapper()
            var initializeScanActionsCalls = 0

            DiagnosticsViewModelBootstrapper(diagnosticsBootstrapper).initialize(
                scope = this,
                initializeScanActions = { initializeScanActionsCalls += 1 },
            )

            assertEquals(1, initializeScanActionsCalls)
            advanceUntilIdle()
            assertEquals(1, diagnosticsBootstrapper.initializeCalls)
        }
}
