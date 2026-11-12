package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.NetworkHandoverMonitor
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.WarpPayloadGenSuggestion
import com.poyka.ripdpi.diagnostics.SystemPrivateDnsStatus
import com.poyka.ripdpi.diagnostics.mapPrivateDnsModeToStatus
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.security.BiometricCapabilityChecker
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import com.poyka.ripdpi.settings.state.toUiState
import com.poyka.ripdpi.ui.screens.diagnostics.state.label
import com.poyka.ripdpi.ui.state.SettingsUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SettingsStateSubscriptionMillis = 5_000L
private const val SettingsRememberedPolicyLimit = 64

internal data class SettingsUiStateAssemblySnapshot(
    val settings: AppSettings,
    val serviceTelemetry: ServiceTelemetrySnapshot,
    val rememberedNetworkCount: Int,
    val hostAutolearnStorePresent: Boolean,
    val biometricAvailability: Int,
    val routingProtectionSnapshot: RoutingProtectionCatalogSnapshot,
    val warpSuggestion: WarpPayloadGenSuggestion?,
    val seqovlSupported: Boolean,
    val systemPrivateDnsStatus: SystemPrivateDnsStatus = SystemPrivateDnsStatus.UNKNOWN,
)

private data class SettingsUiNetworkState(
    val warpSuggestion: WarpPayloadGenSuggestion? = null,
    val systemPrivateDnsStatus: SystemPrivateDnsStatus = SystemPrivateDnsStatus.UNKNOWN,
)

private data class SettingsUiStateStaticAssemblySnapshot(
    val settings: AppSettings,
    val rememberedNetworkCount: Int,
    val hostAutolearnStorePresent: Boolean,
    val biometricAvailability: Int,
    val routingProtectionSnapshot: RoutingProtectionCatalogSnapshot,
    val seqovlSupported: Boolean,
) {
    fun withTelemetry(
        serviceTelemetry: ServiceTelemetrySnapshot,
        network: SettingsUiNetworkState,
    ): SettingsUiStateAssemblySnapshot =
        SettingsUiStateAssemblySnapshot(
            settings = settings,
            serviceTelemetry = serviceTelemetry,
            rememberedNetworkCount = rememberedNetworkCount,
            hostAutolearnStorePresent = hostAutolearnStorePresent,
            biometricAvailability = biometricAvailability,
            routingProtectionSnapshot = routingProtectionSnapshot,
            warpSuggestion = network.warpSuggestion,
            seqovlSupported = seqovlSupported,
            systemPrivateDnsStatus = network.systemPrivateDnsStatus,
        )
}

internal class SettingsUiStateAssembler
    @Inject
    constructor(
        private val biometricCapabilityChecker: BiometricCapabilityChecker,
        private val dispatchers: AppCoroutineDispatchers,
        private val networkHandoverMonitor: NetworkHandoverMonitor,
    ) {
        fun assemble(
            scope: CoroutineScope,
            settingsUiDependencies: SettingsUiDependencies,
            hostAutolearnStoreRefresh: StateFlow<Int>,
        ): StateFlow<SettingsUiState> {
            val networkStates =
                networkRefreshes()
                    .map { buildNetworkState(settingsUiDependencies) }
                    .catch { failure ->
                        if (failure is CancellationException || failure !is Exception) throw failure
                        emit(SettingsUiNetworkState())
                    }.distinctUntilChanged()
                    .flowOn(dispatchers.io)
            val staticSnapshots =
                combine(
                    settingsUiDependencies.appSettingsRepository.settings,
                    hostAutolearnStoreRefresh,
                    settingsUiDependencies.rememberedPolicySource.observePolicies(
                        limit = SettingsRememberedPolicyLimit,
                    ),
                ) { settings, _, rememberedPolicies ->
                    buildStaticAssemblySnapshot(
                        settings = settings,
                        rememberedNetworkCount = rememberedPolicies.size,
                        settingsUiDependencies = settingsUiDependencies,
                    )
                }.flowOn(dispatchers.io)
            return combine(
                staticSnapshots,
                settingsUiDependencies.serviceStateStore.telemetry,
                networkStates,
            ) { staticSnapshot, telemetry, network ->
                buildUiState(staticSnapshot.withTelemetry(telemetry, network))
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(SettingsStateSubscriptionMillis),
                initialValue = SettingsUiState(isHydrated = false),
            )
        }

        private fun networkRefreshes(): Flow<Unit> =
            channelFlow {
                // Start observation before capture so a handover cannot become a silent baseline.
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        networkHandoverMonitor.observe().collect { send(Unit) }
                    } catch (cancelled: CancellationException) {
                        this@channelFlow.cancel(cancelled)
                        throw cancelled
                    }
                }
                send(Unit)
            }

        internal fun buildAssemblySnapshot(
            settings: AppSettings,
            serviceTelemetry: ServiceTelemetrySnapshot,
            rememberedNetworkCount: Int,
            settingsUiDependencies: SettingsUiDependencies,
        ): SettingsUiStateAssemblySnapshot =
            buildStaticAssemblySnapshot(
                settings = settings,
                rememberedNetworkCount = rememberedNetworkCount,
                settingsUiDependencies = settingsUiDependencies,
            ).withTelemetry(serviceTelemetry, buildNetworkState(settingsUiDependencies))

        private fun buildStaticAssemblySnapshot(
            settings: AppSettings,
            rememberedNetworkCount: Int,
            settingsUiDependencies: SettingsUiDependencies,
        ): SettingsUiStateStaticAssemblySnapshot =
            SettingsUiStateStaticAssemblySnapshot(
                settings = settings,
                rememberedNetworkCount = rememberedNetworkCount,
                hostAutolearnStorePresent = settingsUiDependencies.hostAutolearnStoreController.hasStore(),
                biometricAvailability = biometricCapabilityChecker.canAuthenticate(),
                routingProtectionSnapshot = settingsUiDependencies.routingProtectionCatalogService.snapshot(),
                seqovlSupported = settingsUiDependencies.enginePlatformCapabilities.seqovlSupported(),
            )

        private fun buildNetworkState(settingsUiDependencies: SettingsUiDependencies): SettingsUiNetworkState {
            val networkSnapshot = runCatching { settingsUiDependencies.networkSnapshotProvider.capture() }.getOrNull()
            return SettingsUiNetworkState(
                warpSuggestion =
                    networkSnapshot?.let {
                        runCatching { settingsUiDependencies.warpPayloadGenCatalog.suggestFor(it) }.getOrNull()
                    },
                // Presentation only; this does not change VPN DNS policy.
                systemPrivateDnsStatus = mapPrivateDnsModeToStatus(networkSnapshot?.privateDnsMode),
            )
        }

        internal fun buildUiState(snapshot: SettingsUiStateAssemblySnapshot): SettingsUiState =
            snapshot.settings.toUiState(
                serviceStatus = snapshot.serviceTelemetry.status,
                proxyTelemetry = snapshot.serviceTelemetry.proxyTelemetry,
                serviceTelemetry = snapshot.serviceTelemetry,
                hostAutolearnStorePresent = snapshot.hostAutolearnStorePresent,
                rememberedNetworkCount = snapshot.rememberedNetworkCount,
                runtimeOverrideRememberedPolicy = snapshot.rememberedNetworkCount > 0,
                biometricAvailability = snapshot.biometricAvailability,
                routingProtectionSnapshot = snapshot.routingProtectionSnapshot,
                suggestedWarpAmneziaPresetId =
                    snapshot.warpSuggestion
                        ?.preset
                        ?.id
                        .orEmpty(),
                suggestedWarpAmneziaPresetLabel =
                    snapshot.warpSuggestion
                        ?.preset
                        ?.label
                        .orEmpty(),
                seqovlSupported = snapshot.seqovlSupported,
                systemPrivateDnsStatus = snapshot.systemPrivateDnsStatus,
            )
    }
