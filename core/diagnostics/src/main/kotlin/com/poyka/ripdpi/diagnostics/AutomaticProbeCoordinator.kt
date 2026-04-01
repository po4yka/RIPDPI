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
        if (!settings.networkStrategyMemoryEnabled) {
            return Eligibility.Rejected(RejectionReason.SETTINGS_DISABLED)
        }
        if (settings.enableCmdSettings) {
            return Eligibility.Rejected(RejectionReason.CMD_SETTINGS_ACTIVE)
        }
        if (event.usedRememberedPolicy && event.classification != CLASSIFICATION_STRATEGY_FAILURE) {
            return Eligibility.Rejected(RejectionReason.USED_REMEMBERED_POLICY)
        }
        if (hasActiveScan) {
            return Eligibility.Rejected(RejectionReason.ACTIVE_SCAN_RUNNING)
        }
        val probeKey = probeKey(event)
        val lastRunAt = recentRuns[probeKey]
        if (lastRunAt != null && now - lastRunAt < cooldownMs) {
            return Eligibility.Rejected(RejectionReason.COOLDOWN_ACTIVE)
        }
        if (event.classification !in SupportedHandoverClassifications) {
            return Eligibility.Rejected(RejectionReason.UNSUPPORTED_HANDOVER_CLASS)
        }
        if (event.currentCaptivePortalDetected) {
            return Eligibility.Rejected(RejectionReason.CAPTIVE_NETWORK)
        }
        if (!event.currentNetworkValidated) {
            return Eligibility.Rejected(RejectionReason.UNVALIDATED_NETWORK)
        }
        return Eligibility.Eligible
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
        if (failureClasses.any { it != NetworkHandoverFailureClass }) {
            return true
        }
        if (failureClasses.isNotEmpty()) {
            return false
        }
        return connectionState == FailedConnectionState
    }

    fun probeKey(event: PolicyHandoverEvent): String = "${event.mode.preferenceValue}|${event.currentFingerprintHash}"

    const val CLASSIFICATION_STRATEGY_FAILURE = "strategy_failure"

    private val SupportedHandoverClassifications =
        setOf("transport_switch", "link_refresh", CLASSIFICATION_STRATEGY_FAILURE)
    private const val RecentFailureLookbackMs = 15L * 60L * 1000L
    private const val FailedConnectionState = "Failed"
    private const val NetworkHandoverFailureClass = "network_handover"
}
