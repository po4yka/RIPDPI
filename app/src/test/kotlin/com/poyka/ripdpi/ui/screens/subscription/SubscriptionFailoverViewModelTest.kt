package com.poyka.ripdpi.ui.screens.subscription

import app.cash.turbine.test
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeEvent
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionLifecycleState
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionFailoverViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class ResourceStringResolver : StringResolver {
        private val strings =
            mapOf(
                R.string.subscription_failover_no_servers to "No subscription servers observed yet",
                R.string.subscription_failover_last_check_unknown to "last check unknown",
                R.string.subscription_failover_no_active_server to "No active server",
                R.string.subscription_failover_no_switchovers to "no switchovers observed",
                R.string.subscription_failover_active_server_label to "Server %1\$d: %2\$s",
                R.string.subscription_failover_position_label to "server %1\$d/%2\$d",
                R.string.subscription_failover_detail_current_server to "current server",
                R.string.subscription_failover_detail_available_on_switch to "available if the app switches",
                R.string.subscription_failover_event_switched_to_backup to "switched to backup",
                R.string.subscription_failover_event_switch_recorded to "server switch recorded",
                R.string.subscription_failover_using_backup_path_for to "using backup path for %1\$s",
                R.string.subscription_failover_using_backup_path_reason to "using backup path: %1\$s",
                R.string.subscription_failover_last_check_seconds_ago to "last check %1\$ds ago",
                R.string.subscription_failover_last_check_minutes_ago to "last check %1\$dm ago",
                R.string.subscription_failover_last_check_at to "last check at %1\$s",
                R.string.subscription_failover_status_checking to "checking",
                R.string.subscription_failover_status_down to "down",
                R.string.subscription_failover_status_unknown to "unknown",
                R.string.subscription_failover_status_up to "up",
                R.string.subscription_failover_summary_format to "server %1\$d/%2\$d %3\$s · %4\$s · %5\$s",
                R.string.subscription_failover_time_unknown to "time unknown",
                R.string.subscription_signal_banner_expired_title to "Subscription expired",
                R.string.subscription_signal_banner_revoked_title to "Subscription revoked",
                R.string.subscription_signal_banner_unavailable_title to "Subscription unavailable",
                R.string.subscription_signal_banner_stale_title to "Relay pool may be stale",
                R.string.subscription_signal_banner_multiple_message to
                    "%1\$d subscriptions need attention. Cached relay entries may be outdated.",
                R.string.subscription_signal_notification_expired to
                    "%1\$s has expired. Cached relay entries may be outdated.",
                R.string.subscription_signal_notification_revoked to
                    "%1\$s was revoked. Cached relay entries may no longer work.",
                R.string.subscription_signal_notification_unavailable to
                    "%1\$s is unavailable. Cached relay entries may be outdated.",
                R.string.subscription_signal_notification_stale to
                    "%1\$s could not refresh. Cached relay entries may be stale.",
            )

        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String {
            val template = requireNotNull(strings[resId]) { "Unexpected string resource: $resId" }
            return if (formatArgs.isEmpty()) template else String.format(Locale.US, template, *formatArgs)
        }
    }

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
                    proxyGroupRepository = FakeProxyGroupRepository(),
                    relayProfileStore =
                        FakeRelayProfileStore(
                            listOf(
                                relayProfile("01_primary", "primary.example.net"),
                                relayProfile("02_backup", "backup.example.net"),
                            ),
                        ),
                    stringResolver = ResourceStringResolver(),
                )

            viewModel.uiState.test {
                val state = awaitItemUntil { it.hasServers }
                assertEquals("Server 2: backup.example.net", state.activeServerLabel)
                assertEquals(
                    R.string.subscription_failover_status_up,
                    state.servers
                        .single { it.id == "02_backup" }
                        .status
                        .labelRes,
                )
                assertTrue(state.summary.contains("server 2/2 up"))
                assertTrue(state.summary.contains("switched to backup"))
                assertTrue(state.summary.contains("last check"))
                assertTrue(state.events.any { it.message == "primary server stopped answering" })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `repository lifecycle transition reactively surfaces an expired banner`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val viewModel =
                SubscriptionFailoverViewModel(
                    serviceStateStore = FakeServiceStateStore(ServiceTelemetrySnapshot()),
                    proxyGroupRepository = repository,
                    relayProfileStore = FakeRelayProfileStore(emptyList()),
                    stringResolver = ResourceStringResolver(),
                )

            viewModel.uiState.test {
                assertEquals(null, awaitItem().subscriptionAlert)
                repository.setGroups(
                    listOf(
                        subscriptionGroup(
                            "expired",
                            Subscription(lifecycleState = SubscriptionLifecycleState.EXPIRED),
                        ),
                    ),
                )
                val alert = awaitItemUntil { it.subscriptionAlert != null }.subscriptionAlert!!
                assertEquals(SubscriptionAlertTone.ERROR, alert.tone)
                assertEquals("Subscription expired", alert.title)
                assertTrue(alert.message.contains("expired"))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `severity prefers terminal state and recent transient failure stays hidden`() {
        val strings = ResourceStringResolver()
        val now = 10_000L
        val recentTransient =
            subscriptionGroup(
                "recent",
                Subscription(
                    autoUpdateDelay = 60L,
                    lastUpdated = now - 1_000L,
                    lastRefreshAttemptAtEpochMillis = now,
                    lastRefreshFailure = SubscriptionRefreshFailure.NETWORK_ERROR,
                ),
            )
        val healthyUi =
            SubscriptionFailoverMapper.toUiState(
                profiles = emptyList(),
                groups = listOf(recentTransient),
                snapshot = NativeRuntimeSnapshot.idle("relay"),
                nowMillis = now,
                strings = strings,
            )
        assertEquals(null, healthyUi.subscriptionAlert)

        val terminalUi =
            SubscriptionFailoverMapper.toUiState(
                profiles = emptyList(),
                groups =
                    listOf(
                        subscriptionGroup(
                            "stale",
                            Subscription(
                                lastRefreshAttemptAtEpochMillis = 1L,
                                lastRefreshFailure = SubscriptionRefreshFailure.NETWORK_ERROR,
                            ),
                        ),
                        subscriptionGroup(
                            "revoked",
                            Subscription(
                                lifecycleState = SubscriptionLifecycleState.SUSPENDED,
                                lastRefreshFailure = SubscriptionRefreshFailure.REVOKED,
                            ),
                        ),
                    ),
                snapshot = NativeRuntimeSnapshot.idle("relay"),
                nowMillis = now,
                strings = strings,
            )
        assertEquals(SubscriptionAlertTone.ERROR, terminalUi.subscriptionAlert?.tone)
        assertEquals("Subscription revoked", terminalUi.subscriptionAlert?.title)
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

private fun subscriptionGroup(
    id: String,
    subscription: Subscription,
): ProxyGroup =
    ProxyGroup(
        id = id,
        name = "Group $id",
        type = ProxyGroupType.SUBSCRIPTION,
        order = 0,
        isSelector = false,
        subscription = subscription,
    )

private class FakeProxyGroupRepository : ProxyGroupRepository {
    private val state = MutableStateFlow<List<ProxyGroup>>(emptyList())

    fun setGroups(groups: List<ProxyGroup>) {
        state.value = groups
    }

    override suspend fun add(group: ProxyGroup) {
        state.value = state.value.filterNot { it.id == group.id } + group
    }

    override suspend fun update(group: ProxyGroup) {
        state.value = state.value.map { if (it.id == group.id) group else it }
    }

    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun list(): List<ProxyGroup> = state.value

    override fun groups(): Flow<List<ProxyGroup>> = state
}

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
