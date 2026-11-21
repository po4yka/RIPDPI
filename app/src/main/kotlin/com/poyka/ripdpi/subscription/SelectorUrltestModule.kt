package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkHandoverStates
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.proxyimport.SelectorReloadScope
import com.poyka.ripdpi.services.selector.SelectorRuntimeLifecycleListener
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import javax.inject.Singleton

/** Hilt wiring for the urltest latency prober and its lifecycle coordinator. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SelectorUrltestModule {
    @Binds
    @Singleton
    abstract fun bindMemberLatencyProbe(probe: RelayPayloadMemberLatencyProbe): MemberLatencyProbe

    companion object {
        @Provides
        @Singleton
        internal fun provideSelectorUrltestProber(
            selectionStore: SelectorSelectionStore,
            probe: MemberLatencyProbe,
            scopeProvider: CurrentSelectorProbeScopeProvider,
            vpn: SelectorVpnPayloadProbe,
            qualityStore: com.poyka.ripdpi.data.ProfileQualityStore,
            budgetLedger: com.poyka.ripdpi.data.ProfileQualityBudgetLedger,
        ): SelectorUrltestProber =
            SelectorUrltestProber(
                selectionStore = selectionStore,
                probe = probe,
                scopeProvider = scopeProvider,
                monotonicMillis = android.os.SystemClock::elapsedRealtime,
                qualityStore = qualityStore,
                reserveBudget = budgetLedger::reserve,
                measureVpnPayload = vpn::measure,
            )

        @Provides
        @Singleton
        fun provideSelectorUrltestCoordinator(
            @SelectorReloadScope scope: CoroutineScope,
            groupRepository: ProxyGroupRepository,
            prober: SelectorUrltestProber,
            network: com.poyka.ripdpi.services.PhysicalNetworkObserver,
            settings: com.poyka.ripdpi.data.AppSettingsRepository,
            service: com.poyka.ripdpi.data.ServiceStateStore,
            activeGroup: com.poyka.ripdpi.data.selector.SelectorActiveGroupStore,
        ): SelectorUrltestCoordinator =
            SelectorUrltestCoordinator(
                scope = scope,
                groupRepository = groupRepository,
                prober = prober,
                scopeChanges =
                    merge(
                        network.changes.map { },
                        settings.settings.map { },
                        service.status.map { },
                        activeGroup.choiceChanges.map { },
                    ),
                recoveryRequests = selectorRecoveryRequests(network.changes, service),
            )

        /** Adapts the urltest coordinator to the service-lifecycle multibinding. */
        @Provides
        @Singleton
        @IntoSet
        fun provideSelectorUrltestLifecycleListener(
            coordinator: SelectorUrltestCoordinator,
        ): SelectorRuntimeLifecycleListener =
            object : SelectorRuntimeLifecycleListener {
                override suspend fun prepare() = coordinator.awaitSubscription()

                override fun start(owner: Mode) = coordinator.start(owner)

                override fun stop(owner: Mode) = coordinator.stop(owner)
            }
    }
}

internal fun selectorRecoveryRequests(
    networkChanges: Flow<Long>,
    service: ServiceStateStore,
): Flow<Unit> =
    merge(
        networkChanges.drop(1).map { },
        service.events
            .filterIsInstance<ServiceEvent.Failed>()
            .filter {
                it.statusAtFailure == AppStatus.Running && it.modeAtFailure == service.status.value.second &&
                    it.reason !is FailureReason.PermissionLost
            }.map { },
        service.telemetry
            .map { it.networkHandoverState }
            .distinctUntilChanged()
            .drop(1)
            .filter { it == NetworkHandoverStates.Revalidated }
            .map { },
    )
