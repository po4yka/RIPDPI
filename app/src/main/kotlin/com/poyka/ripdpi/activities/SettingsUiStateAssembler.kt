package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.WarpPayloadGenSuggestion
import com.poyka.ripdpi.diagnostics.SystemPrivateDnsStatus
import com.poyka.ripdpi.diagnostics.mapPrivateDnsModeToStatus
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.security.BiometricCapabilityChecker
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import com.poyka.ripdpi.settings.state.toUiState
import com.poyka.ripdpi.ui.state.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
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

private data class SettingsUiStateStaticAssemblySnapshot(
    val settings: AppSettings,
    val rememberedNetworkCount: Int,
    val hostAutolearnStorePresent: Boolean,
    val biometricAvailability: Int,
    val routingProtectionSnapshot: RoutingProtectionCatalogSnapshot,
    val warpSuggestion: WarpPayloadGenSuggestion?,
    val seqovlSupported: Boolean,
    val systemPrivateDnsStatus: SystemPrivateDnsStatus,
) {
    fun withTelemetry(serviceTelemetry: ServiceTelemetrySnapshot): SettingsUiStateAssemblySnapshot =
        SettingsUiStateAssemblySnapshot(
            settings = settings,
            serviceTelemetry = serviceTelemetry,
            rememberedNetworkCount = rememberedNetworkCount,
            hostAutolearnStorePresent = hostAutolearnStorePresent,
            biometricAvailability = biometricAvailability,
            routingProtectionSnapshot = routingProtectionSnapshot,
            warpSuggestion = warpSuggestion,
            seqovlSupported = seqovlSupported,
            systemPrivateDnsStatus = systemPrivateDnsStatus,
        )
}

internal class SettingsUiStateAssembler
    @Inject
    constructor(
        private val biometricCapabilityChecker: BiometricCapabilityChecker,
        private val dispatchers: AppCoroutineDispatchers,
    ) {
        fun assemble(
            scope: CoroutineScope,
            settingsUiDependencies: SettingsUiDependencies,
            hostAutolearnStoreRefresh: StateFlow<Int>,
        ): StateFlow<SettingsUiState> {
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
            ) { staticSnapshot, telemetry ->
                buildUiState(staticSnapshot.withTelemetry(telemetry))
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(SettingsStateSubscriptionMillis),
                initialValue = SettingsUiState(isHydrated = false),
            )
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
            ).withTelemetry(serviceTelemetry)

        private fun buildStaticAssemblySnapshot(
            settings: AppSettings,
            rememberedNetworkCount: Int,
            settingsUiDependencies: SettingsUiDependencies,
        ): SettingsUiStateStaticAssemblySnapshot {
            val networkSnapshot =
                runCatching { settingsUiDependencies.networkSnapshotProvider.capture() }.getOrNull()
            return SettingsUiStateStaticAssemblySnapshot(
                settings = settings,
                rememberedNetworkCount = rememberedNetworkCount,
                hostAutolearnStorePresent = settingsUiDependencies.hostAutolearnStoreController.hasStore(),
                biometricAvailability = biometricCapabilityChecker.canAuthenticate(),
                routingProtectionSnapshot = settingsUiDependencies.routingProtectionCatalogService.snapshot(),
                warpSuggestion =
                    networkSnapshot?.let {
                        runCatching {
                            settingsUiDependencies.warpPayloadGenCatalog.suggestFor(it)
                        }.getOrNull()
                    },
                seqovlSupported = settingsUiDependencies.enginePlatformCapabilities.seqovlSupported(),
                // Derived Kotlin-side from the captured snapshot's privateDnsMode;
                // informational only and never a VPN DNS policy source.
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
