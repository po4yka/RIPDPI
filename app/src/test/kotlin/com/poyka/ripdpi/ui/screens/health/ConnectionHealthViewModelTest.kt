package com.poyka.ripdpi.ui.screens.health

import app.cash.turbine.test
import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.services.ConnectionHealthBucket
import com.poyka.ripdpi.services.ConnectionHealthDestinationClass
import com.poyka.ripdpi.services.ConnectionHealthRepository
import com.poyka.ripdpi.services.ConnectionHealthSnapshot
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionHealthViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `ui state maps seeded repository rates and quality`() =
        runTest {
            val repository =
                FakeConnectionHealthRepository(
                    ConnectionHealthSnapshot(
                        buckets =
                            listOf(
                                ConnectionHealthBucket(
                                    destinationClass = ConnectionHealthDestinationClass.YOUTUBE,
                                    activeStrategy = "quic:sni_split",
                                    successCount = 9,
                                    failureCount = 1,
                                    attributedCount = 4,
                                    lastUpdatedAt = 1_000L,
                                ),
                            ),
                        quality = ConnectionQualitySnapshot(lossPct = 10f, rttP50Ms = 55, sampleCount = 9),
                        observedAt = 1_000L,
                    ),
                )
            val viewModel = ConnectionHealthViewModel(repository)

            viewModel.uiState.test {
                val state = awaitItem()
                val youtube = state.rows.single()
                assertEquals(ConnectionHealthDestinationClass.YOUTUBE, youtube.destinationClass)
                assertEquals(90, youtube.successRatePercent)
                assertEquals("quic:sni_split", youtube.activeStrategy)
                assertEquals(10, state.qualityLossPercent)
                assertEquals(55L, state.qualityRttP50Ms)
                cancelAndIgnoreRemainingEvents()
            }
        }
}

private class FakeConnectionHealthRepository(
    initial: ConnectionHealthSnapshot,
) : ConnectionHealthRepository {
    override val snapshots: StateFlow<ConnectionHealthSnapshot> = MutableStateFlow(initial)
}
