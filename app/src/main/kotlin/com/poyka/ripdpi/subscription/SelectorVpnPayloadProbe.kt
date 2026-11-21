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
    val usesIsolatedProbes: Boolean = false,
)

/** True when this configuration is a later revision of [previous] that applies [memberId] for [groupId]. */
internal fun AppliedRuntimeConfiguration.isAppliedSelectorSuccessor(
    previous: AppliedRuntimeConfiguration,
    groupId: String,
    memberId: String?,
): Boolean =
    memberId != null && runtimeId == previous.runtimeId && mode == previous.mode && revision > previous.revision &&
        effectiveSelection.selectorGroupId == groupId && effectiveSelection.selectorMemberId == memberId

internal class SelectorVpnPayloadProbe
    @Inject
    constructor(
        private val applied: AppliedRuntimeConfigurationSource,
        private val diagnostics: DiagnosticsRuntimeCoordinator,
        private val settings: AppSettingsRepository,
        private val http: CandidateHttpPayloadProbe,
    ) {
        /**
         * With noDomain, the active member needs an isolated candidate probe but the applied VPN revision still
         * owns the choice. Resolving the control names in the app would leave the tunnel.
         */
        suspend fun capture(groupId: String): SelectorVpnPath? {
            val application = applied.applications.value[Mode.VPN]
            val configuration =
                application?.runningConfiguration()?.takeIf { it.effectiveSelection.selectorGroupId == groupId }
            if (configuration == null) return null
            val isolated = settings.snapshot().noDomain
            val lease =
                if (!isolated && application is RuntimeConfigurationApplication.Applied) {
                    (diagnostics.acquireInPathRouteLease() as? InPathRouteLeaseAcquisition.Acquired)?.lease
                } else {
                    null
                }
            val path = SelectorVpnPath(configuration, lease, isolated)
            return path.takeIf(::isCurrent) ?: path.copy(lease = null)
        }

        fun isCurrent(path: SelectorVpnPath): Boolean {
            val application = applied.applications.value[Mode.VPN] as? RuntimeConfigurationApplication.Applied
            return application?.configuration == path.configuration &&
                (
                    path.usesIsolatedProbes || path.lease?.let {
                        it.runtimeId == path.configuration.runtimeId && diagnostics.validateInPathRouteLease(it) == null
                    } == true
                )
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
