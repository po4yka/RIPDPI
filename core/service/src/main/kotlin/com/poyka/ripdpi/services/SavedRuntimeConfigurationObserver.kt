package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileMutationGenerationSource
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.rules.RuleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Process-owned saved projection; reading saved configuration never activates or probes a runtime. */
@Singleton
internal class SavedRuntimeConfigurationObserver
    @Inject
    constructor(
        @param:ApplicationScope private val scope: CoroutineScope,
        private val settings: AppSettingsRepository,
        private val mutations: ProfileMutationGenerationSource,
        private val groups: ProxyGroupRepository,
        private val rules: RuleRepository,
        private val awg: AwgEgressSelectionProvider,
        private val capture: RequestedRuntimeConfigurationCapture,
    ) {
        fun observe(store: AppliedRuntimeConfigurationStore) {
            scope.launch {
                combine(settings.settings, mutations.generation, groups.groups(), rules.enabledRules()) {
                    saved,
                    _,
                    _,
                    _,
                    ->
                    saved
                }.collectLatest { saved ->
                    for (mode in Mode.entries) {
                        val identity =
                            try {
                                capture
                                    .capture(
                                        mode,
                                        saved,
                                        if (mode ==
                                            Mode.VPN
                                        ) {
                                            awg.selectedAwgEgress()
                                        } else {
                                            null
                                        },
                                    ).identity
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Invalid/unavailable saved configuration cannot be compared or called applied.
                                null
                            }
                        store.observeSaved(mode, identity)
                    }
                }
            }
        }
    }
