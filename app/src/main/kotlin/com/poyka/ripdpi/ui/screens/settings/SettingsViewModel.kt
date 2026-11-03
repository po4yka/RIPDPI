package com.poyka.ripdpi.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.message
import com.poyka.ripdpi.activities.messageResource
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.security.PinVerifyResult
import com.poyka.ripdpi.services.ServiceIntentArbiter
import com.poyka.ripdpi.ui.components.bufferForUiLifecycle
import com.poyka.ripdpi.ui.state.SettingsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private val PinKeyLostNotice =
    SettingsEffect.Notice(
        title = R.string.notice_pin_key_lost_title,
        message = R.string.notice_pin_key_lost_message,
        tone = SettingsNoticeTone.Warning,
    )

internal fun AppSettings.settingsNotice(): SettingsEffect.Notice? =
    if (appLockResetNoticePending) PinKeyLostNotice else null

@Suppress("TooManyFunctions")
@HiltViewModel
class SettingsViewModel
    @Suppress("LongParameterList")
    @Inject
    internal constructor(
        private val settingsUiDependencies: SettingsUiDependencies,
        private val settingsActionDependencies: SettingsActionDependencies,
        private val settingsViewModelBootstrapper: SettingsViewModelBootstrapper,
        settingsUiStateAssembler: SettingsUiStateAssembler,
        reconnectCoordinator: com.poyka.ripdpi.services.RunningServiceReconnect,
        serviceIntentArbiter: ServiceIntentArbiter,
    ) : ViewModel() {
        private val settingsEffects =
            MutableSharedFlow<SettingsEffect>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        private val hostAutolearnStoreRefresh = MutableStateFlow(0)
        private val strategyPackCatalogState = MutableStateFlow(StrategyPackCatalogUiState())
        private val mutations =
            SettingsMutationRunner(
                viewModelScope,
                settingsUiDependencies.appSettingsRepository,
                settingsEffects,
            )

        // The Settings list owns the PIN-key-lost notice: the app-lock controls it explains are on that screen.
        // It follows the persisted flag, so it outlives the process until the user acknowledges it or sets a new PIN.
        val settingsNotice: StateFlow<SettingsEffect.Notice?> =
            settingsUiDependencies.appSettingsRepository.settings
                .map { it.settingsNotice() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        // The Advanced screen owns pack-refresh notices; a notice raised while it is away waits here.
        private val advancedNoticeState = SettingsNoticeState()
        val advancedNotice: StateFlow<SettingsEffect.Notice?> = advancedNoticeState.latest

        // The DNS screen owns its reconnect failure; kept apart so it never shows up in Advanced.
        private val dnsNoticeState = SettingsNoticeState()
        val dnsNotice: StateFlow<SettingsEffect.Notice?> = dnsNoticeState.latest

        fun acknowledgeAdvancedNotice(notice: SettingsEffect.Notice) = advancedNoticeState.acknowledge(notice)

        fun acknowledgeDnsNotice(notice: SettingsEffect.Notice) = dnsNoticeState.acknowledge(notice)

        // The battery request has its own flow: the Settings list pane and a detail beside it are both resumed on
        // wide windows, and a single-delivery flow would hand the event to the wrong one.
        val batteryOptimizationRequests =
            settingsEffects
                .filterIsInstance<SettingsEffect.RequestDisableBatteryOptimization>()
                .bufferForUiLifecycle(viewModelScope)
        val hostPackCatalog: StateFlow<HostPackCatalogUiState> =
            settingsActionDependencies.hostPackCatalogUiStateStore.state
        val strategyPackCatalog: StateFlow<StrategyPackCatalogUiState> = strategyPackCatalogState

        val uiState: StateFlow<SettingsUiState> =
            settingsUiStateAssembler.assemble(
                scope = viewModelScope,
                settingsUiDependencies = settingsUiDependencies,
                hostAutolearnStoreRefresh = hostAutolearnStoreRefresh,
            )

        /**
         * Live embedded-Xray provider snapshot for the Settings provider status
         * row. Null on the native provider path or when no Xray session is active.
         */
        val xrayProviderSnapshot: StateFlow<XrayProviderSnapshot?> =
            settingsActionDependencies.serviceStateStore.telemetry
                .map { it.xrayProviderSnapshot }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue =
                        settingsActionDependencies.serviceStateStore.telemetry.value.xrayProviderSnapshot,
                )

        internal val dnsActions =
            SettingsDnsActions(
                mutations = mutations,
                serviceStateStore = settingsActionDependencies.serviceStateStore,
                reconnectCoordinator = reconnectCoordinator,
                onReconnectFailure = { result ->
                    dnsNoticeState.post(
                        SettingsEffect.Notice(
                            com.poyka.ripdpi.R.string.runtime_config_failed,
                            result.reason.messageResource(),
                            SettingsNoticeTone.Error,
                        ),
                    )
                },
                onReconnectSuccess = dnsNoticeState::clear,
                serviceIntentArbiter = serviceIntentArbiter,
            )
        internal val customizationActions by lazy {
            SettingsCustomizationActions(
                mutations = mutations,
                launcherIconController = settingsActionDependencies.launcherIconController,
                startOnBootController = settingsActionDependencies.startOnBootController,
                currentUiState = { uiState.value },
                pinVerifier = settingsActionDependencies.pinVerifier,
                pinLockoutManager = settingsActionDependencies.pinLockoutManager,
            )
        }
        internal val maintenanceActions by lazy {
            SettingsMaintenanceActions(
                learnedHostsEraser = settingsActionDependencies.learnedHostsEraser,
                rememberedPolicySource = settingsActionDependencies.rememberedPolicySource,
                strategyPackService = settingsActionDependencies.strategyPackService,
                strategyPackStateStore = settingsActionDependencies.strategyPackStateStore,
                hostPackCatalogUiStateCoordinator = settingsActionDependencies.hostPackCatalogUiStateCoordinator,
                hostPackCatalogUiStateStore = settingsActionDependencies.hostPackCatalogUiStateStore,
                telemetrySaltStore = settingsActionDependencies.telemetrySaltStore,
                mutations = mutations,
                hostAutolearnStoreRefresh = hostAutolearnStoreRefresh,
                strategyPackCatalogState = strategyPackCatalogState,
                currentUiState = { uiState.value },
                currentServiceStatus = { settingsActionDependencies.serviceStateStore.status.value.first },
            )
        }

        init {
            // Subscribed before anything can emit, so no notice is missed.
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                settingsEffects.filterIsInstance<SettingsEffect.Notice>().collect(advancedNoticeState::post)
            }
            settingsViewModelBootstrapper.initialize(
                scope = viewModelScope,
                loadInitialHostPackCatalog = maintenanceActions::loadInitialHostPackCatalog,
                loadInitialStrategyPackCatalog = maintenanceActions::loadInitialStrategyPackCatalog,
                updateStrategyPackCatalogRuntimeState = { runtimeState ->
                    strategyPackCatalogState.value =
                        strategyPackCatalogState.value.copy(
                            runtimeState = runtimeState,
                            isRefreshing = false,
                        )
                },
            )
        }

        fun update(transform: SettingsMutation) {
            mutations.update(transform)
        }

        fun updateSetting(
            key: String,
            value: String,
            transform: SettingsMutation,
        ) {
            mutations.updateSetting(key = key, value = value, transform = transform)
        }

        suspend fun updateSettingAndAwait(
            key: String,
            value: String,
            transform: SettingsMutation,
        ) {
            mutations.updateSettingAndAwait(key = key, value = value, transform = transform)
        }

        fun clearCommunityCache() {
            viewModelScope.launch {
                settingsActionDependencies.communityComparisonStore.clear()
            }
        }

        fun verifyBackupPin(pin: String): PinVerifyResult = customizationActions.verifyBackupPin(pin)

        fun isPinLockedOut(): Boolean = settingsActionDependencies.pinLockoutManager.isLockedOut()

        fun pinLockoutRemainingMs(): Long = settingsActionDependencies.pinLockoutManager.remainingLockoutMs()
    }
