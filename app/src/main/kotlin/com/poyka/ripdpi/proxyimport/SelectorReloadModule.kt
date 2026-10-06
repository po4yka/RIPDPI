package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.selector.SelectorSelectionStore
import com.poyka.ripdpi.services.selector.SelectorReloadCoordinator
import com.poyka.ripdpi.services.selector.SelectorReloadTrigger
import com.poyka.ripdpi.services.selector.SelectorRuntimeLifecycleListener
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The selected-member signal that drives a live selector hot-reload: the active
 * selector group's `selectedProfileId`, tracked reactively so the coordinator
 * always follows whichever selector group is current.
 *
 * The active group is persisted explicitly. The historical first-group choice is
 * migrated once; standalone activation clears it and inactive groups never reload
 * the live runtime.
 */
@Singleton
class ActiveSelectorSelectionProvider
    @Inject
    constructor(
        private val groupRepository: ProxyGroupRepository,
        private val selectionStore: SelectorSelectionStore,
        private val activeGroup: com.poyka.ripdpi.data.selector.SelectorActiveGroupStore,
    ) {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun selectionChanges(): Flow<com.poyka.ripdpi.services.selector.SelectorReloadRequest?> =
            groupRepository
                .groups()
                .onEach { groups ->
                    activeGroup.initializeLegacyGroup(
                        groups
                            .filter { it.isSelector && it.members.isNotEmpty() }
                            .sortedBy { it.order }
                            .map { it.id },
                    )
                    activeGroup.prune(groups.map { it.id }.toSet())
                }.combine(activeGroup.activeGroupId) { groups, groupId ->
                    groupId?.takeIf { id -> groups.any { it.id == id && it.isSelector && it.members.isNotEmpty() } }
                }.distinctUntilChanged()
                .flatMapLatest { groupId ->
                    if (groupId == null) {
                        flowOf(null)
                    } else {
                        combine(selectionStore.selectedProfileId(groupId), activeGroup.choiceChanges) { memberId, _ ->
                            val receipt =
                                selectionStore.manualReceipt(
                                    groupId,
                                ) as? com.poyka.ripdpi.data.ProfileActivationReceipt
                            val reconstructedManual = selectionStore.snapshot(groupId).isManual && receipt == null
                            memberId?.takeUnless { reconstructedManual }?.let {
                                com.poyka.ripdpi.services.selector
                                    .SelectorReloadRequest(groupId, it, receipt)
                            }
                        }
                    }
                }

        fun activeGroupId(): String? = activeGroup.activeGroupId.value
    }

/** Qualifier for the application-lifetime scope the selector coordinator watches on. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SelectorReloadScope

@Module
@InstallIn(SingletonComponent::class)
abstract class SelectorReloadModule {
    @Binds
    @Singleton
    abstract fun bindSelectorReloadTrigger(trigger: RelayActivationSelectorReloadTrigger): SelectorReloadTrigger

    @Binds
    @Singleton
    abstract fun bindRunningRelayRefresher(refresher: ServiceControllerRunningRelayRefresher): RunningRelayRefresher

    companion object {
        @Provides
        @Singleton
        @SelectorReloadScope
        fun provideSelectorReloadScope(): CoroutineScope = CoroutineScope(SupervisorJob())

        @Provides
        @Singleton
        fun provideSelectorReloadCoordinator(
            @SelectorReloadScope scope: CoroutineScope,
            selectionProvider: ActiveSelectorSelectionProvider,
            trigger: SelectorReloadTrigger,
        ): SelectorReloadCoordinator =
            SelectorReloadCoordinator(
                scope = scope,
                selectionChanges = selectionProvider.selectionChanges(),
                trigger = trigger,
            )

        /** Adapts the reload coordinator to the service-lifecycle multibinding. */
        @Provides
        @Singleton
        @IntoSet
        fun provideSelectorReloadLifecycleListener(
            coordinator: SelectorReloadCoordinator,
            trigger: RelayActivationSelectorReloadTrigger,
        ): SelectorRuntimeLifecycleListener =
            object : SelectorRuntimeLifecycleListener {
                override suspend fun prepare() {
                    coordinator.awaitSubscription()
                    trigger.prepare()
                }

                override suspend fun afterStart() = trigger.afterStart()

                override fun start(owner: Mode) = coordinator.start(owner)

                override fun stop(owner: Mode) = coordinator.stop(owner)
            }
    }
}
