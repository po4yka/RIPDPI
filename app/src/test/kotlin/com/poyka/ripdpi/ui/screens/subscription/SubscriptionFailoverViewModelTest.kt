package com.poyka.ripdpi.ui.screens.subscription

import app.cash.turbine.test
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionFailoverViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `ui state maps active backup server and seeded failover events`() =
        runTest {
            val now = System.currentTimeMillis()
            val serviceStateStore =
                FakeServiceStateStore(
                    ServiceTelemetrySnapshot(
                        relayTelemetryStatus = RuntimeTelemetryStatus(state = RuntimeTelemetryState.Snapshot),
                        relayTelemetry =
                            NativeRuntimeSnapshot(
                                source = "relay",
                                state = "running",
                                health = "healthy",
                                profileId = "02_backup",
                                fallbackMode = "automatic",
                                resolverFallbackActive = true,
                                resolverFallbackReason = "primary timed out",
                                nativeEvents =
                                    listOf(
                                        NativeRuntimeEvent(
                                            source = "relay",
                                            level = "info",
                                            message = "primary server stopped answering",
                                            createdAt = now - 60_000L,
                                            kind = "failover",
                                        ),
                                    ),
                                capturedAt = now - 30_000L,
                            ),
                    ),
                )
            val viewModel =
                SubscriptionFailoverViewModel(
                    serviceStateStore = serviceStateStore,
                    relayProfileStore =
                        FakeRelayProfileStore(
                            listOf(
                                relayProfile("01_primary", "primary.example.net"),
                                relayProfile("02_backup", "backup.example.net"),
                            ),
                        ),
                )

            viewModel.uiState.test {
                val state = awaitItemUntil { it.hasServers }
                assertEquals("Server 2: backup.example.net", state.activeServerLabel)
                assertEquals(
                    "up",
                    state.servers
                        .single { it.id == "02_backup" }
                        .status
                        .label,
                )
                assertTrue(state.summary.contains("server 2/2 up"))
                assertTrue(state.summary.contains("switched to backup"))
                assertTrue(state.summary.contains("last check"))
                assertTrue(state.events.any { it.message == "primary server stopped answering" })
                cancelAndIgnoreRemainingEvents()
            }
        }
}

private suspend fun app.cash.turbine.ReceiveTurbine<SubscriptionFailoverUiState>.awaitItemUntil(
    predicate: (SubscriptionFailoverUiState) -> Boolean,
): SubscriptionFailoverUiState {
    repeat(8) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
    return awaitItem()
}

private fun relayProfile(
    id: String,
    server: String,
): RelayProfileRecord =
    RelayProfileRecord(
        id = id,
        server = server,
        serverPort = 443,
        serverName = server,
    )

private class FakeRelayProfileStore(
    private val profiles: List<RelayProfileRecord>,
) : RelayProfileStore {
    override suspend fun load(profileId: String): RelayProfileRecord? = profiles.firstOrNull { it.id == profileId }

    override suspend fun list(): List<RelayProfileRecord> = profiles

    override suspend fun save(profile: RelayProfileRecord) = Unit

    override suspend fun clear(profileId: String) = Unit
}

private class FakeServiceStateStore(
    initialTelemetry: ServiceTelemetrySnapshot,
) : ServiceStateStore {
    override val status: StateFlow<Pair<AppStatus, Mode>> = MutableStateFlow(AppStatus.Running to Mode.VPN)
    override val events: SharedFlow<ServiceEvent> = MutableSharedFlow()
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = MutableStateFlow(initialTelemetry)

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) = Unit

    override fun emitFailed(
        sender: Sender,
        reason: FailureReason,
    ) = Unit

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) = Unit
}
