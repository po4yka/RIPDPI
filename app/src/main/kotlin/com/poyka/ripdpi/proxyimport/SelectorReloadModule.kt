package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyGroup
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The selected-member signal that drives a live selector hot-reload: the active
 * selector group's `selectedProfileId`, tracked reactively so the coordinator
 * always follows whichever selector group is current.
 *
 * The "active" selector group is the first `isSelector` group with stored members
 * (ordered by [ProxyGroup.order]); when none exists the signal stays `null`. This
 * is the same notion the launcher/shortcut selection path uses
 * (`SelectorSelectionStore.select(groupId, …)`): a single selector group owns the
 * live exit at a time.
 */
@Singleton
class ActiveSelectorSelectionProvider
    @Inject
    constructor(
        private val groupRepository: ProxyGroupRepository,
        private val selectionStore: SelectorSelectionStore,
    ) {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun selectedProfileId(): Flow<String?> =
            groupRepository
                .groups()
                .map(::activeSelectorGroupId)
                .distinctUntilChanged()
                .flatMapLatest { groupId ->
                    if (groupId == null) flowOf(null) else selectionStore.selectedProfileId(groupId)
                }

        private fun activeSelectorGroupId(groups: List<ProxyGroup>): String? =
            groups
                .filter { it.isSelector && it.members.isNotEmpty() }
                .minByOrNull { it.order }
                ?.id
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
                selectedProfileId = selectionProvider.selectedProfileId(),
                trigger = trigger,
            )

        /** Adapts the reload coordinator to the service-lifecycle multibinding. */
        @Provides
        @Singleton
        @IntoSet
        fun provideSelectorReloadLifecycleListener(
            coordinator: SelectorReloadCoordinator,
        ): SelectorRuntimeLifecycleListener =
            object : SelectorRuntimeLifecycleListener {
                override fun start() = coordinator.start()

                override fun stop() = coordinator.stop()
            }
    }
}
