package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.WarpPayloadGenSuggestion
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.security.BiometricAuthManager
import com.poyka.ripdpi.services.RoutingProtectionCatalogSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

internal data class SettingsUiStateAssemblySnapshot(
    val settings: AppSettings,
    val serviceTelemetry: com.poyka.ripdpi.data.ServiceTelemetrySnapshot,
    val rememberedNetworkCount: Int,
    val hostAutolearnStorePresent: Boolean,
    val biometricAvailability: Int,
    val routingProtectionSnapshot: RoutingProtectionCatalogSnapshot,
    val warpSuggestion: WarpPayloadGenSuggestion?,
)

internal class SettingsUiStateAssembler
    @Inject
    constructor() {
        private val biometricAuthManager = BiometricAuthManager()

        fun assemble(
            scope: CoroutineScope,
            settingsUiDependencies: SettingsUiDependencies,
            hostAutolearnStoreRefresh: StateFlow<Int>,
        ): StateFlow<SettingsUiState> =
            combine(
                settingsUiDependencies.appSettingsRepository.settings,
                settingsUiDependencies.serviceStateStore.telemetry,
                hostAutolearnStoreRefresh,
                settingsUiDependencies.rememberedPolicySource.observePolicies(limit = 64),
            ) { settings, telemetry, _, rememberedPolicies ->
                buildUiState(
                    buildAssemblySnapshot(
                        settings = settings,
                        serviceTelemetry = telemetry,
                        rememberedNetworkCount = rememberedPolicies.size,
                        settingsUiDependencies = settingsUiDependencies,
                    ),
                )
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = buildUiState(initialSnapshot(settingsUiDependencies)),
            )

        internal fun buildAssemblySnapshot(
            settings: AppSettings,
            serviceTelemetry: com.poyka.ripdpi.data.ServiceTelemetrySnapshot,
            rememberedNetworkCount: Int,
            settingsUiDependencies: SettingsUiDependencies,
        ): SettingsUiStateAssemblySnapshot =
            SettingsUiStateAssemblySnapshot(
                settings = settings,
                serviceTelemetry = serviceTelemetry,
                rememberedNetworkCount = rememberedNetworkCount,
                hostAutolearnStorePresent = settingsUiDependencies.hostAutolearnStoreController.hasStore(),
                biometricAvailability =
                    biometricAuthManager.canAuthenticate(settingsUiDependencies.application),
                routingProtectionSnapshot =
                    settingsUiDependencies.routingProtectionCatalogService.snapshot(),
                warpSuggestion =
                    runCatching {
                        settingsUiDependencies.warpPayloadGenCatalog.suggestFor(
                            settingsUiDependencies.networkSnapshotProvider.capture(),
                        )
                    }.getOrNull(),
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
            )

        private fun initialSnapshot(settingsUiDependencies: SettingsUiDependencies): SettingsUiStateAssemblySnapshot =
            buildAssemblySnapshot(
                settings = AppSettingsSerializer.defaultValue,
                serviceTelemetry = settingsUiDependencies.serviceStateStore.telemetry.value,
                rememberedNetworkCount = 0,
                settingsUiDependencies = settingsUiDependencies,
            )
    }
