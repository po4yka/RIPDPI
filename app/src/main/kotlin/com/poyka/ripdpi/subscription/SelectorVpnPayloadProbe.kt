package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppliedRuntimeConfiguration
import com.poyka.ripdpi.data.AppliedRuntimeConfigurationSource
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.InPathRouteLeaseAcquisition
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PayloadFailoverPolicy
import com.poyka.ripdpi.data.ProfileQualityReason
import com.poyka.ripdpi.data.RuntimeConfigurationApplication
import com.poyka.ripdpi.data.runningConfiguration
import com.poyka.ripdpi.services.CandidateHttpPayloadProbe
import com.poyka.ripdpi.services.RelayProbeEndpoint
import javax.inject.Inject

internal data class SelectorVpnPath(
    val configuration: AppliedRuntimeConfiguration,
    val lease: DiagnosticsInPathRouteLease?,
)

internal class SelectorVpnPayloadProbe
    @Inject
    constructor(
        private val applied: AppliedRuntimeConfigurationSource,
        private val diagnostics: DiagnosticsRuntimeCoordinator,
        private val settings: AppSettingsRepository,
        private val http: CandidateHttpPayloadProbe,
    ) {
        /**
         * Returns null when the VPN does not carry [groupId], or when noDomain is on: the in-path listener cannot
         * resolve control host names then, and resolving them in the app would leave the tunnel (as the field
         * report does). The selector then measures the active member with isolated candidate probes.
         */
        suspend fun capture(groupId: String): SelectorVpnPath? {
            if (settings.snapshot().noDomain) return null
            val application = applied.applications.value[Mode.VPN]
            val configuration =
                application?.runningConfiguration()?.takeIf { it.effectiveSelection.selectorGroupId == groupId }
                    ?: return null
            val lease =
                if (application is RuntimeConfigurationApplication.Applied) {
                    (diagnostics.acquireInPathRouteLease() as? InPathRouteLeaseAcquisition.Acquired)?.lease
                } else {
                    null
                }
            val path = SelectorVpnPath(configuration, lease)
            return path.takeIf(::isCurrent) ?: path.copy(lease = null)
        }

        fun isCurrent(path: SelectorVpnPath): Boolean {
            val lease = path.lease ?: return false
            val application = applied.applications.value[Mode.VPN] as? RuntimeConfigurationApplication.Applied
            return application?.configuration == path.configuration &&
                lease.runtimeId == path.configuration.runtimeId &&
                diagnostics.validateInPathRouteLease(lease) == null
        }

        suspend fun measure(
            path: SelectorVpnPath,
            policy: PayloadFailoverPolicy,
        ): MemberProbeResult? {
            val lease = path.lease?.takeIf { isCurrent(path) } ?: return null
            val endpoint =
                RelayProbeEndpoint(
                    lease.host,
                    lease.port,
                    lease.credentials,
                )
            val result = http.probePayload(endpoint, policy)
            return MemberProbeResult(
                result.latencyMillis.takeIf { result.succeeded },
                result.quality,
                if (result.succeeded) ProfileQualityReason.Verified else ProfileQualityReason.TransferFailed,
            ).takeIf { isCurrent(path) }
        }
    }
