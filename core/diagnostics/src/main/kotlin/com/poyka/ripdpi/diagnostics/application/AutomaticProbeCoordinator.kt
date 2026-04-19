@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity

internal object AutomaticProbeCoordinator {
    sealed interface Eligibility {
        data object Eligible : Eligibility

        data class Rejected(
            val reason: RejectionReason,
        ) : Eligibility
    }

    enum class RejectionReason {
        SETTINGS_DISABLED,
        CMD_SETTINGS_ACTIVE,
        USED_REMEMBERED_POLICY,
        UNSUPPORTED_HANDOVER_CLASS,
        VALIDATED_POLICY_ALREADY_EXISTS,
        CAPTIVE_NETWORK,
        UNVALIDATED_NETWORK,
        MISSING_RECENT_FAILURE_SIGNAL,
        ACTIVE_SCAN_RUNNING,
        COOLDOWN_ACTIVE,
    }

    fun evaluateBaseEligibility(
        settings: com.poyka.ripdpi.proto.AppSettings,
        event: PolicyHandoverEvent,
        hasActiveScan: Boolean,
        recentRuns: Map<String, Long>,
        cooldownMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Eligibility {
        val probeKey = probeKey(event)
        val lastRunAt = recentRuns[probeKey]
        val rejectionReason =
            when {
                !settings.networkStrategyMemoryEnabled -> RejectionReason.SETTINGS_DISABLED

                settings.enableCmdSettings -> RejectionReason.CMD_SETTINGS_ACTIVE

                event.usedRememberedPolicy &&
                    event.classification != CLASSIFICATION_STRATEGY_FAILURE -> RejectionReason.USED_REMEMBERED_POLICY

                hasActiveScan -> RejectionReason.ACTIVE_SCAN_RUNNING

                lastRunAt != null && now - lastRunAt < cooldownMs -> RejectionReason.COOLDOWN_ACTIVE

                event.classification !in SupportedHandoverClassifications -> RejectionReason.UNSUPPORTED_HANDOVER_CLASS

                event.currentCaptivePortalDetected -> RejectionReason.CAPTIVE_NETWORK

                !event.currentNetworkValidated -> RejectionReason.UNVALIDATED_NETWORK

                else -> null
            }
        return if (rejectionReason != null) Eligibility.Rejected(rejectionReason) else Eligibility.Eligible
    }

    fun evaluateRememberedPolicyEligibility(hasValidatedRememberedMatch: Boolean): Eligibility =
        if (hasValidatedRememberedMatch) {
            Eligibility.Rejected(RejectionReason.VALIDATED_POLICY_ALREADY_EXISTS)
        } else {
            Eligibility.Eligible
        }

    fun evaluateRecentFailureSignal(sample: TelemetrySampleEntity?): Eligibility =
        if (sample?.hasQualifyingFailureSignal() == true) {
            Eligibility.Eligible
        } else {
            Eligibility.Rejected(RejectionReason.MISSING_RECENT_FAILURE_SIGNAL)
        }

    fun recentFailureLookbackMs(): Long = RecentFailureLookbackMs

    private fun TelemetrySampleEntity.hasQualifyingFailureSignal(): Boolean {
        val failureClasses = listOfNotNull(failureClass, lastFailureClass)
        return when {
            failureClasses.any { it != NetworkHandoverFailureClass } -> true
            failureClasses.isNotEmpty() -> false
            else -> connectionState == FailedConnectionState
        }
    }

    fun probeKey(event: PolicyHandoverEvent): String = "${event.mode.preferenceValue}|${event.currentFingerprintHash}"

    const val CLASSIFICATION_STRATEGY_FAILURE = "strategy_failure"

    private val SupportedHandoverClassifications =
        setOf("transport_switch", "link_refresh", CLASSIFICATION_STRATEGY_FAILURE)
    private const val RecentFailureLookbackMs = 15L * 60L * 1000L
    private const val FailedConnectionState = "Failed"
    private const val NetworkHandoverFailureClass = "network_handover"
}
