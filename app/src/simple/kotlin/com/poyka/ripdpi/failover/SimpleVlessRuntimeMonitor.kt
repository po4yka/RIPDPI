package com.poyka.ripdpi.failover

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.seed.SEED_RELAY_PROFILE_ID_PREFIX
import com.poyka.ripdpi.seed.SimpleFlavorSessionWatcher
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime surface for the simple flavor's primary user traffic path.
 *
 * The embedded VLESS+Reality profile is always selected by the startup seeder and is never raced
 * against another transport. If a startup attempt fails before reaching [AppStatus.Running], this
 * watcher advances through the bounded recovery chain VLESS+Reality -> embedded Hysteria2 -> AWG.
 * It does not enable telemetry-driven switching after readiness or loop after AWG fails.
 */
@Singleton
internal class SimpleVlessRuntimeMonitor
    @Inject
    constructor(
        private val serviceStateStore: ServiceStateStore,
        private val settingsRepository: AppSettingsRepository,
        private val relayProfileStore: RelayProfileStore,
        private val awgFallbackSelection: SimpleAwgFallbackSelection,
        private val serviceController: ServiceController,
    ) : SimpleFlavorSessionWatcher,
        ActiveTransportProvider {
        private val _activeTransport = MutableStateFlow<ActiveTransportDescriptor?>(null)
        private var startupFallbackStage = StartupFallbackStage.Vless
        private var activeHysteriaProfileId: String? = null
        private var activeAwgProfileId: String? = null

        override val activeTransport: StateFlow<ActiveTransportDescriptor?> = _activeTransport.asStateFlow()

        override fun bind(scope: CoroutineScope) {
            scope.launch {
                serviceStateStore.status.collect { (status, mode) ->
                    val settings = settingsRepository.snapshot()
                    val runningRelayKind =
                        when {
                            status != AppStatus.Running || mode != Mode.VPN -> null

                            settings.relayEnabled &&
                                settings.relayKind == RelayKindVlessReality -> RelayKindVlessReality

                            settings.relayEnabled &&
                                settings.relayKind == RelayKindHysteria2 &&
                                settings.relayProfileId == activeHysteriaProfileId -> RelayKindHysteria2

                            !settings.relayEnabled &&
                                activeAwgProfileId != null &&
                                settings.simpleFailoverAwgProfileId == activeAwgProfileId -> AMNEZIA_WG_KIND

                            else -> null
                        }
                    _activeTransport.value =
                        runningRelayKind?.let { relayKind ->
                            ActiveTransportDescriptor(protocolKind = relayKind)
                        }
                    if (runningRelayKind == RelayKindVlessReality) {
                        startupFallbackStage = StartupFallbackStage.Vless
                        activeHysteriaProfileId = null
                        activeAwgProfileId = null
                    }
                }
            }
            scope.launch {
                serviceStateStore.events
                    .filterIsInstance<ServiceEvent.Failed>()
                    .collect(::recoverStartupFailure)
            }
        }

        /**
         * // NOT cancel-safe: each selected fallback is persisted before restart. If cancellation
         * lands after a write, the next start remains fail-closed on that fallback rather than
         * silently bypassing the VPN.
         */
        private suspend fun recoverStartupFailure(event: ServiceEvent.Failed) {
            if (
                event.sender != Sender.VPN ||
                event.modeAtFailure != Mode.VPN ||
                event.statusAtFailure == AppStatus.Running ||
                !event.reason.isRecoverableStartupFailure()
            ) {
                return
            }
            when (startupFallbackStage) {
                StartupFallbackStage.Vless -> recoverVlessWithHysteria2()
                StartupFallbackStage.Hysteria2 -> recoverHysteria2WithAwg()
                StartupFallbackStage.Awg -> Unit
            }
        }

        private suspend fun recoverVlessWithHysteria2() {
            val settingsAtFailure = settingsRepository.snapshot()
            if (
                !settingsAtFailure.relayEnabled ||
                settingsAtFailure.relayKind != RelayKindVlessReality ||
                settingsAtFailure.relayProfileId != SEEDED_VLESS_REALITY_PROFILE_ID
            ) {
                return
            }

            startupFallbackStage = StartupFallbackStage.Hysteria2
            serviceStateStore.status.first { (status, mode) ->
                status == AppStatus.Halted && mode == Mode.VPN
            }
            val currentSettings = settingsRepository.snapshot()
            if (
                !currentSettings.relayEnabled ||
                currentSettings.relayKind != RelayKindVlessReality ||
                currentSettings.relayProfileId != settingsAtFailure.relayProfileId
            ) {
                Logger.i { "SimpleVlessRuntimeMonitor: startup selection changed; skipping stale fallback" }
                return
            }

            val fallback = relayProfileStore.list().firstSeededHysteria2OrNull()
            if (fallback == null) {
                Logger.w { "SimpleVlessRuntimeMonitor: embedded Hysteria2 fallback unavailable; remaining halted" }
                return
            }
            settingsRepository.update {
                setRelayEnabled(true)
                setRelayKind(RelayKindHysteria2)
                setRelayProfileId(fallback.id)
                setSimpleFailoverAwgProfileId("")
            }
            activeHysteriaProfileId = fallback.id
            Logger.i { "SimpleVlessRuntimeMonitor: VLESS startup failed; retrying with embedded Hysteria2" }
            val result = serviceController.start(Mode.VPN)
            if (result is ServiceStartResult.Rejected) {
                Logger.w { "SimpleVlessRuntimeMonitor: Hysteria2 startup recovery rejected — ${result.reason}" }
            }
        }

        private suspend fun recoverHysteria2WithAwg() {
            val settingsAtFailure = settingsRepository.snapshot()
            if (
                !settingsAtFailure.relayEnabled ||
                settingsAtFailure.relayKind != RelayKindHysteria2 ||
                settingsAtFailure.relayProfileId != activeHysteriaProfileId
            ) {
                return
            }

            startupFallbackStage = StartupFallbackStage.Awg
            serviceStateStore.status.first { (status, mode) ->
                status == AppStatus.Halted && mode == Mode.VPN
            }
            val currentSettings = settingsRepository.snapshot()
            if (
                !currentSettings.relayEnabled ||
                currentSettings.relayKind != RelayKindHysteria2 ||
                currentSettings.relayProfileId != settingsAtFailure.relayProfileId
            ) {
                Logger.i { "SimpleVlessRuntimeMonitor: Hysteria2 selection changed; skipping stale AWG fallback" }
                return
            }

            val awgRequest = awgFallbackSelection.firstAvailable()
            if (awgRequest == null) {
                Logger.w { "SimpleVlessRuntimeMonitor: AWG fallback unavailable; remaining halted" }
                return
            }
            settingsRepository.update {
                setRelayEnabled(false)
                setSimpleFailoverAwgProfileId(awgRequest.profileId)
            }
            awgFallbackSelection.select(awgRequest)
            activeAwgProfileId = awgRequest.profileId
            Logger.i { "SimpleVlessRuntimeMonitor: Hysteria2 startup failed; retrying with amneziawg" }
            val result = serviceController.start(Mode.VPN)
            if (result is ServiceStartResult.Rejected) {
                Logger.w { "SimpleVlessRuntimeMonitor: AWG startup recovery rejected — ${result.reason}" }
            }
        }

        private fun FailureReason.isRecoverableStartupFailure(): Boolean =
            when (this) {
                is FailureReason.NativeError,
                FailureReason.TunnelEstablishmentFailed,
                is FailureReason.InitialTransportSelectionFailed,
                is FailureReason.RelayConfigRejected,
                is FailureReason.RelayFingerprintPolicyRejected,
                -> true

                is FailureReason.PermissionLost,
                is FailureReason.Unexpected,
                is FailureReason.WarpEndpointUnavailable,
                is FailureReason.WarpProvisioningFailed,
                is FailureReason.WarpRuntimeFailed,
                -> false
            }

        private fun List<RelayProfileRecord>.firstSeededHysteria2OrNull(): RelayProfileRecord? =
            asSequence()
                .filter { profile ->
                    profile.kind == RelayKindHysteria2 &&
                        (
                            profile.id == SEEDED_HYSTERIA2_PROFILE_ID ||
                                profile.id.startsWith("$SEEDED_HYSTERIA2_PROFILE_ID-")
                        )
                }.minWithOrNull(
                    compareBy(
                        { profile -> seededOccurrence(profile.id) },
                        RelayProfileRecord::id,
                    ),
                )

        private fun seededOccurrence(profileId: String): Int =
            when {
                profileId == SEEDED_HYSTERIA2_PROFILE_ID -> {
                    0
                }

                profileId.startsWith("$SEEDED_HYSTERIA2_PROFILE_ID-") -> {
                    profileId.removePrefix("$SEEDED_HYSTERIA2_PROFILE_ID-").toIntOrNull()?.minus(1)
                        ?: Int.MAX_VALUE
                }

                else -> {
                    Int.MAX_VALUE
                }
            }

        private companion object {
            const val AMNEZIA_WG_KIND = "amneziawg"
            const val SEEDED_VLESS_REALITY_PROFILE_ID = "${SEED_RELAY_PROFILE_ID_PREFIX}VlessReality"
            const val SEEDED_HYSTERIA2_PROFILE_ID = "${SEED_RELAY_PROFILE_ID_PREFIX}Hysteria2"
        }

        private enum class StartupFallbackStage {
            Vless,
            Hysteria2,
            Awg,
        }
    }
