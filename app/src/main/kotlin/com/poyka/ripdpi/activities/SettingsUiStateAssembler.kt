package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.WarpPayloadGenSuggestion
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.security.BiometricCapabilityChecker
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import com.poyka.ripdpi.settings.state.toUiState
import com.poyka.ripdpi.ui.state.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
)

private data class SettingsUiStateStaticAssemblySnapshot(
    val settings: AppSettings,
    val rememberedNetworkCount: Int,
    val hostAutolearnStorePresent: Boolean,
    val biometricAvailability: Int,
    val routingProtectionSnapshot: RoutingProtectionCatalogSnapshot,
    val warpSuggestion: WarpPayloadGenSuggestion?,
    val seqovlSupported: Boolean,
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
        )
}

internal class SettingsUiStateAssembler
    @Inject
    constructor(
        private val biometricCapabilityChecker: BiometricCapabilityChecker,
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
                }
            return combine(
                staticSnapshots,
                settingsUiDependencies.serviceStateStore.telemetry,
            ) { staticSnapshot, telemetry ->
                buildUiState(staticSnapshot.withTelemetry(telemetry))
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(SettingsStateSubscriptionMillis),
                initialValue = buildUiState(initialSnapshot(settingsUiDependencies)),
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
        ): SettingsUiStateStaticAssemblySnapshot =
            SettingsUiStateStaticAssemblySnapshot(
                settings = settings,
                rememberedNetworkCount = rememberedNetworkCount,
                hostAutolearnStorePresent = settingsUiDependencies.hostAutolearnStoreController.hasStore(),
                biometricAvailability = biometricCapabilityChecker.canAuthenticate(),
                routingProtectionSnapshot = settingsUiDependencies.routingProtectionCatalogService.snapshot(),
                warpSuggestion =
                    runCatching {
                        settingsUiDependencies.warpPayloadGenCatalog.suggestFor(
                            settingsUiDependencies.networkSnapshotProvider.capture(),
                        )
                    }.getOrNull(),
                seqovlSupported = settingsUiDependencies.enginePlatformCapabilities.seqovlSupported(),
            )

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
            )

        private fun initialSnapshot(settingsUiDependencies: SettingsUiDependencies): SettingsUiStateAssemblySnapshot =
            buildAssemblySnapshot(
                settings = AppSettingsSerializer.defaultValue,
                serviceTelemetry = settingsUiDependencies.serviceStateStore.telemetry.value,
                rememberedNetworkCount = 0,
                settingsUiDependencies = settingsUiDependencies,
            )
    }
