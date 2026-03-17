package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.PolicyHandoverEvent

internal object AutomaticProbeCoordinator {

    fun shouldLaunchProbe(
        settings: com.poyka.ripdpi.proto.AppSettings,
        event: PolicyHandoverEvent,
        hasActiveScan: Boolean,
        recentRuns: Map<String, Long>,
        cooldownMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!settings.networkStrategyMemoryEnabled || settings.enableCmdSettings || event.usedRememberedPolicy) {
            return false
        }
        if (hasActiveScan) {
            return false
        }
        val probeKey = probeKey(event)
        val lastRunAt = recentRuns[probeKey]
        if (lastRunAt != null && now - lastRunAt < cooldownMs) {
            return false
        }
        return true
    }

    fun probeKey(event: PolicyHandoverEvent): String =
        "${event.mode.preferenceValue}|${event.currentFingerprintHash}"
}
